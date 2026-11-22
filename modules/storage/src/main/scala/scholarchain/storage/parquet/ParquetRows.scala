package scholarchain.storage.parquet

import java.nio.file.Path
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.{ColumnIOFactory, LocalInputFile, RecordReader}
import org.apache.parquet.schema.{GroupType, LogicalTypeAnnotation, PrimitiveType, Type}
import scholarchain.storage.common.{StorageError, StorageException}

/** Streaming Parquet reader. Rows are produced one at a time, one row group at a time: a table is
  * never held in memory. A row is a `ujson.Obj` whose keys are the column names, so a row decodes
  * through the core wire codec exactly like a JSON record.
  *
  * Mapping (matches what Spark writes): optional absent value -> null; LIST (3-level standard,
  * or legacy 2-level) -> array; MAP -> object keyed by the string key; other group -> object;
  * INT32/INT64/FLOAT/DOUBLE -> number (an INT64 beyond 2^53 is refused, not rounded);
  * BINARY -> string (UTF-8); BOOLEAN -> boolean.
  */
object ParquetRows:

  /** Row count from the file footer; reads no data pages. */
  def rowCount(file: Path): Long =
    val r = ParquetFileReader.open(new LocalInputFile(file))
    try r.getRecordCount
    finally r.close()

  /** Opens a streaming iterator. The caller must close it (also closed at the last row). */
  def open(file: Path): ParquetRowIterator = new ParquetRowIterator(file)

  /** Runs `f` over the rows of `file` and always closes the reader. */
  def foreach(file: Path)(f: ujson.Obj => Unit): Unit =
    val it = open(file)
    try it.foreach(f)
    finally it.close()

  private val MaxExactLong = 1L << 53

  private[parquet] def toJson(group: Group, schema: GroupType): ujson.Obj =
    val out = ujson.Obj()
    var i = 0
    while i < schema.getFieldCount do
      val field = schema.getType(i)
      out(field.getName) = fieldValue(group, i, field)
      i += 1
    out

  private def fieldValue(group: Group, index: Int, field: Type): ujson.Value =
    val count = group.getFieldRepetitionCount(index)
    if field.isRepetition(Type.Repetition.REPEATED) then
      // a repeated field not wrapped in a LIST annotation is a legacy list
      ujson.Arr.from((0 until count).map(n => value(group, index, n, field)))
    else if count == 0 then ujson.Null
    else value(group, index, 0, field)

  private def value(group: Group, index: Int, n: Int, field: Type): ujson.Value =
    if field.isPrimitive then
      field.asPrimitiveType.getPrimitiveTypeName match
        case PrimitiveType.PrimitiveTypeName.BINARY |
            PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY =>
          ujson.Str(group.getString(index, n))
        case PrimitiveType.PrimitiveTypeName.INT32 => ujson.Num(group.getInteger(index, n).toDouble)
        case PrimitiveType.PrimitiveTypeName.INT64 =>
          val l = group.getLong(index, n)
          if math.abs(l) > MaxExactLong then
            throw new StorageException(
              StorageError.InvalidRecord(
                s"column ${field.getName}: $l exceeds 2^53 and cannot be carried exactly"
              )
            )
          ujson.Num(l.toDouble)
        case PrimitiveType.PrimitiveTypeName.DOUBLE => ujson.Num(group.getDouble(index, n))
        case PrimitiveType.PrimitiveTypeName.FLOAT => ujson.Num(group.getFloat(index, n).toDouble)
        case PrimitiveType.PrimitiveTypeName.BOOLEAN => ujson.Bool(group.getBoolean(index, n))
        case other =>
          throw new StorageException(
            StorageError.InvalidRecord(s"column ${field.getName}: unsupported Parquet type $other")
          )
    else
      val gt = field.asGroupType
      val sub = group.getGroup(index, n)
      Option(gt.getLogicalTypeAnnotation) match
        case Some(_: LogicalTypeAnnotation.ListLogicalTypeAnnotation) => listValue(sub, gt)
        case Some(_: LogicalTypeAnnotation.MapLogicalTypeAnnotation) => mapValue(sub, gt)
        case _ => toJson(sub, gt)

  /** LIST: one repeated child; standard form wraps each element in a one-field group. */
  private def listValue(list: Group, gt: GroupType): ujson.Value =
    val repeated = gt.getType(0)
    val count = list.getFieldRepetitionCount(0)
    ujson.Arr.from((0 until count).map { k =>
      if repeated.isPrimitive then value(list, 0, k, repeated)
      else
        val rg = repeated.asGroupType
        val entry = list.getGroup(0, k)
        if rg.getFieldCount == 1 && !isStructLike(rg) then fieldValue(entry, 0, rg.getType(0))
        else toJson(entry, rg)
    })

  // `list { element }` and `bag { array_element }` are wrappers; a repeated group with several
  // fields (legacy list of structs) is the element itself.
  private def isStructLike(rg: GroupType): Boolean =
    !Set("list", "bag", "array").contains(rg.getName) && rg.getFieldCount != 1

  private def mapValue(map: Group, gt: GroupType): ujson.Value =
    val kv = gt.getType(0).asGroupType
    val count = map.getFieldRepetitionCount(0)
    val out = ujson.Obj()
    (0 until count).foreach { k =>
      val entry = map.getGroup(0, k)
      val key = entry.getString(0, 0)
      out(key) = fieldValue(entry, 1, kv.getType(1))
    }
    out

final class ParquetRowIterator(file: Path) extends Iterator[ujson.Obj] with AutoCloseable:
  private val reader = ParquetFileReader.open(new LocalInputFile(file))
  private val schema = reader.getFooter.getFileMetaData.getSchema
  private val columnIO = new ColumnIOFactory().getColumnIO(schema)
  private var recordReader: RecordReader[Group] = null
  private var remainingInGroup = 0L
  private var closed = false

  /** Loads the next non-empty row group, or closes the reader. */
  private def fill(): Unit =
    while remainingInGroup == 0 && !closed do
      val pages: PageReadStore = reader.readNextRowGroup()
      if pages == null then close()
      else
        remainingInGroup = pages.getRowCount
        recordReader = columnIO.getRecordReader(pages, new GroupRecordConverter(schema))

  def hasNext: Boolean =
    if remainingInGroup > 0 then true
    else
      fill()
      remainingInGroup > 0

  def next(): ujson.Obj =
    if !hasNext then throw new NoSuchElementException(s"no more rows in $file")
    remainingInGroup -= 1
    ParquetRows.toJson(recordReader.read(), schema)

  def close(): Unit =
    if !closed then
      closed = true
      reader.close()
