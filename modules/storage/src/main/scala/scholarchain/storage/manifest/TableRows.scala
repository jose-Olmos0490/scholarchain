package scholarchain.storage.manifest

import scholarchain.core.json.WireCodec
import scholarchain.core.validation.RecordContract
import scholarchain.storage.common.*
import scholarchain.storage.parquet.ParquetRows
import upickle.default.ReadWriter

/** A row that did not decode into its core record, with where it was and why. */
final case class RowFailure(file: String, rowIndex: Long, reason: String)

/** One decoded row (or its failure) and the Parquet file it came from. */
final case class SourcedRow[+A](file: String, rowIndex: Long, value: Either[RowFailure, A])

object TableRows:
  /** Streams every row of a verified table, in manifest file order, decoding each through the core
    * wire codec and record contract. Files are opened one at a time and closed at their end.
    * Decoding failures are values, never exceptions, so the caller records them as rejects.
    */
  def stream[A: ReadWriter: RecordContract](
      verified: VerifiedManifest,
      tableName: String
  ): Iterator[SourcedRow[A]] =
    verified.table(tableName) match
      case None => Iterator.empty
      case Some(table) =>
        table.files.iterator.flatMap { f =>
          val local = verified.localFiles(f.path)
          val it = ParquetRows.open(local)
          var index = -1L
          it.map { row =>
            index += 1
            val failure = (reason: String) => RowFailure(f.path, index, reason)
            val decoded: Either[RowFailure, A] =
              try
                WireCodec
                  .decodeValue[A](row)
                  .left
                  .map(es => failure(es.map(_.toString).mkString("; ")))
              catch case e: StorageException => Left(failure(e.error.message))
            SourcedRow(f.path, index, decoded)
          }
        }
