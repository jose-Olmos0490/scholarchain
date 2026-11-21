package scholarchain.hadoop.dblp

import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, Path}
import org.apache.hadoop.io.compress.{
  CodecPool,
  CompressionCodec,
  CompressionCodecFactory,
  Decompressor,
  SplitCompressionInputStream,
  SplittableCompressionCodec
}
import org.apache.hadoop.io.{BytesWritable, LongWritable}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}
import scholarchain.hadoop.io.PipelineConf

/** Input format for DBLP XML that yields one complete top-level record per value.
  *
  * Contract (Phase 4 work order B item 1):
  *   - Records are found by synchronizing on the start tag `<name` (followed by whitespace, `>`
  *     or `/`) of a configured record type (`scholarchain.hadoop.dblpRecordTypes`).
  *   - A record belongs to the split in which its start tag's `<` lies (`start <= pos < end`). It
  *     is read to its matching end tag even past the split end.
  *   - Bytes before the first start tag of a split (the tail of a record owned by the previous
  *     split) are skipped.
  *   - A value ends at its matching end tag or, if a new start tag of a configured type appears
  *     first (a truncated record), just before that tag; the truncated value is emitted for
  *     rejection and the next record starts at that tag (see `readToEnd`).
  *   - Uncompressed and bzip2 input are splittable. Gzip (and every other non-splittable codec)
  *     yields one split per file.
  *
  * Key: byte offset of the record start (uncompressed) or the bzip2 block position that holds it.
  * Value: the raw record bytes in the file's own encoding, from `<name` to the end tag inclusive.
  */
class DblpInputFormat extends FileInputFormat[LongWritable, BytesWritable]:

  override protected def isSplitable(context: JobContext, file: Path): Boolean =
    val codec = new CompressionCodecFactory(context.getConfiguration).getCodec(file)
    codec == null || codec.isInstanceOf[SplittableCompressionCodec]

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext
  ): RecordReader[LongWritable, BytesWritable] = new DblpRecordReader

object DblpInputFormat:
  /** Record element names to synchronize on, from the job configuration. */
  def recordTypes(conf: Configuration): Vector[String] =
    PipelineConf
      .required(conf, PipelineConf.DblpRecordTypes)
      .split(',')
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)

/** Byte scanner behind [[DblpInputFormat]]. Per-task state only. */
class DblpRecordReader extends RecordReader[LongWritable, BytesWritable]:
  private var start = 0L
  private var end = 0L
  private var fileIn: FSDataInputStream = null
  private var in: InputStream = null
  private var splitIn: SplitCompressionInputStream = null
  private var decompressor: Decompressor = null
  private var compressedSplittable = false

  // Uncompressed reading: own buffer, position tracked exactly.
  private val buf = new Array[Byte](64 * 1024)
  private var bufLen = 0
  private var bufPos = 0
  private var bufStartOffset = 0L
  private var bufBlockPos = 0L
  private var eof = false

  private var startTags: Array[Array[Byte]] = Array.empty
  private var names: Array[String] = Array.empty

  private val key = new LongWritable()
  private val value = new BytesWritable()
  private val record = new java.io.ByteArrayOutputStream(16 * 1024)

  override def initialize(genericSplit: InputSplit, context: TaskAttemptContext): Unit =
    val split = genericSplit.asInstanceOf[FileSplit]
    val conf = context.getConfiguration
    names = DblpInputFormat.recordTypes(conf).toArray
    startTags = names.map(n => ("<" + n).getBytes(StandardCharsets.US_ASCII))
    start = split.getStart
    end = start + split.getLength
    val file = split.getPath
    fileIn = file.getFileSystem(conf).open(file)
    val codec: CompressionCodec = new CompressionCodecFactory(conf).getCodec(file)
    if codec == null then
      fileIn.seek(start)
      in = fileIn
      bufStartOffset = start
    else
      decompressor = CodecPool.getDecompressor(codec)
      codec match
        case sc: SplittableCompressionCodec =>
          compressedSplittable = true
          splitIn = sc.createInputStream(
            fileIn,
            decompressor,
            start,
            end,
            SplittableCompressionCodec.READ_MODE.BYBLOCK
          )
          start = splitIn.getAdjustedStart
          end = splitIn.getAdjustedEnd
          in = splitIn
        case other =>
          // Not splittable: the whole file is one split and every record belongs to it.
          in = other.createInputStream(fileIn, decompressor)
          start = 0L
          end = Long.MaxValue

  /** Position used for ownership of the byte at `bufPos` (the next byte to be consumed). */
  private def positionOfNext: Long =
    if compressedSplittable then bufBlockPos else bufStartOffset + bufPos

  private def fill(): Boolean =
    if eof then false
    else
      bufStartOffset += bufLen
      bufLen = 0
      bufPos = 0
      val n = in.read(buf, 0, buf.length)
      if n <= 0 then
        // A bzip2 BYBLOCK read may return 0 at a block boundary; retry until data or EOF.
        if n == 0 then fill()
        else
          eof = true
          bufLen = 0
          bufPos = 0
          false
      else
        bufLen = n
        bufPos = 0
        // In BYBLOCK mode a read never spans a bzip2 block boundary, so the position after the
        // read is the position of the block that holds every byte just read.
        if compressedSplittable then bufBlockPos = splitIn.getPos
        true

  /** Next byte, or -1 at end of stream. */
  private def nextByte(): Int =
    if bufPos >= bufLen && !fill() then -1
    else
      val b = buf(bufPos) & 0xff
      bufPos += 1
      b

  private def peekByte(): Int =
    if bufPos >= bufLen && !fill() then -1 else buf(bufPos) & 0xff

  private def isNameEnd(b: Int): Boolean =
    b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '>' || b == '/'

  /** Scans to the next owned start tag. Leaves `record` holding `<name` and returns the index of
    * the record type, or -1 when the split has no further owned record.
    */
  private def findStart(): Int =
    var found = -1
    var done = false
    while !done do
      // Skip to the next '<'.
      var b = peekByte()
      while b != -1 && b != '<' do
        bufPos += 1
        b = peekByte()
      if b == -1 then done = true
      else
        val tagPos = positionOfNext
        if tagPos >= end then done = true
        else
          nextByte() // consume '<'
          record.reset()
          record.write('<')
          // Read the element name.
          val name = new StringBuilder
          var c = peekByte()
          while c != -1 && !isNameEnd(c) && c != '<' && name.length <= 64 do
            name.append(c.toChar)
            record.write(c)
            nextByte()
            c = peekByte()
          val idx = names.indexOf(name.toString)
          if idx >= 0 && c != -1 && isNameEnd(c) then
            key.set(tagPos)
            found = idx
            done = true
    found

  /** Outcome of reading one record body. */
  private enum Body:
    /** The matching end tag (or `/>`) was read. */
    case Complete

    /** End of stream before the end tag. */
    case TruncatedAtEof

    /** A new top-level start tag of a configured type appeared before the end tag. The current
      * value is the first `cut` bytes of `record`; the rest of `record` is `<name` of the next
      * record, which starts at `nextPos` and has type index `nextIdx`.
      */
    case TruncatedByNext(cut: Int, nextIdx: Int, nextPos: Long)

  // A record start found while reading the previous (truncated) record, not yet emitted.
  private var pendingIdx = -1
  private var pendingPos = 0L
  private var pendingBytes: Array[Byte] = Array.empty

  /** Reads an element name into `record` (bounded); returns it. The byte after it is not consumed. */
  private def readName(): String =
    val name = new StringBuilder
    var c = peekByte()
    while c != -1 && !isNameEnd(c) && c != '<' && name.length <= 64 do
      name.append(c.toChar)
      record.write(c)
      nextByte()
      c = peekByte()
    name.toString

  /** Reads from after `<name` to the matching end tag `</name>` (optional whitespace before `>`)
    * or the `/>` of an empty element.
    *
    * Truncated-record rule: DBLP top-level records never nest and `<` never appears unescaped in
    * character data, so a start tag `<t` (t a configured record type, followed by whitespace, `>`
    * or `/`) before the end tag means the current record was cut off. The current value ends just
    * before that `<` (it is emitted and the parser rejects it as malformed), and the next record
    * starts at that `<`, owned by the split that holds it, exactly as [[findStart]] would decide.
    * The output is therefore independent of split boundaries.
    */
  private def readToEnd(idx: Int): Body =
    val own = names(idx)
    // Finish the start tag (stopping at a '<', which a well-formed start tag cannot contain).
    var prev = -1
    var inQuote = -1
    var closed = false
    var b = peekByte()
    while b != -1 && b != '<' && !closed do
      nextByte()
      record.write(b)
      if b == '>' && inQuote == -1 then closed = true
      else
        if inQuote == -1 && (b == '"' || b == '\'') then inQuote = b
        else if b == inQuote then inQuote = -1
        prev = b
        b = peekByte()
    if closed && prev == '/' then Body.Complete
    else
      var result: Body = null
      while result == null do
        val c = peekByte()
        if c == -1 then result = Body.TruncatedAtEof
        else if c != '<' then
          nextByte()
          record.write(c)
        else
          val tagPos = positionOfNext
          val mark = record.size
          nextByte()
          record.write('<')
          if peekByte() == '/' then
            nextByte()
            record.write('/')
            val name = readName()
            if name == own then
              var w = peekByte()
              while w == ' ' || w == '\t' || w == '\n' || w == '\r' do
                nextByte()
                record.write(w)
                w = peekByte()
              if w == '>' then
                nextByte()
                record.write('>')
                result = Body.Complete
          else
            val name = readName()
            val j = names.indexOf(name)
            val after = peekByte()
            if j >= 0 && after != -1 && isNameEnd(after) then
              result = Body.TruncatedByNext(mark, j, tagPos)
      result

  /** Moves the bytes of a truncated value out of `record` into `value`; keeps the next record's
    * `<name` as pending.
    */
  private def emitTruncated(cut: Int, nextIdx: Int, nextPos: Long): Unit =
    val all = record.toByteArray
    value.set(all, 0, cut)
    pendingBytes = java.util.Arrays.copyOfRange(all, cut, all.length)
    pendingIdx = nextIdx
    pendingPos = nextPos

  /** Next owned record start: the pending one (if this split owns it) or the next found by scan. */
  private def nextStart(): Int =
    if pendingIdx >= 0 then
      val idx = pendingIdx
      pendingIdx = -1
      if pendingPos >= end then -1
      else
        record.reset()
        record.write(pendingBytes)
        key.set(pendingPos)
        idx
    else findStart()

  override def nextKeyValue(): Boolean =
    val idx = nextStart()
    if idx < 0 then false
    else
      readToEnd(idx) match
        case Body.Complete =>
          val bytes = record.toByteArray
          value.set(bytes, 0, bytes.length)
          true
        case Body.TruncatedAtEof =>
          // Truncated final record: handed over as-is; the parser rejects it as malformed rather
          // than it being silently dropped.
          val bytes = record.toByteArray
          value.set(bytes, 0, bytes.length)
          bytes.nonEmpty
        case Body.TruncatedByNext(cut, nextIdx, nextPos) =>
          // Truncated record followed by a new record: emitted on its own for rejection.
          emitTruncated(cut, nextIdx, nextPos)
          true

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: BytesWritable = value

  override def getProgress: Float =
    if end == Long.MaxValue || end == start then 0f
    else math.min(1f, (positionOfNext - start).toFloat / (end - start).toFloat)

  override def close(): Unit =
    try if in != null then in.close()
    finally
      if decompressor != null then
        CodecPool.returnDecompressor(decompressor)
        decompressor = null
