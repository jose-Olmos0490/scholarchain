package scholarchain.spark

import java.util.Arrays
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.spark.SparkTestSupport.spark
import scholarchain.spark.graph.ConnectedComponents

/** Min-label propagation on small graphs: labels, per-group isolation, convergence flag,
  * iteration counts and checkpointing.
  */
class ConnectedComponentsSpec extends AnyFunSuite:
  private val nodeSchema = StructType(
    Seq(StructField("g", IntegerType), StructField("node", StringType))
  )
  private val edgeSchema = StructType(
    Seq(
      StructField("g", IntegerType),
      StructField("src", StringType),
      StructField("dst", StringType)
    )
  )

  private def nodes(rows: (Int, String)*): DataFrame =
    spark.createDataFrame(Arrays.asList(rows.map((g, n) => Row(g, n))*), nodeSchema)
  private def edges(rows: (Int, String, String)*): DataFrame =
    spark.createDataFrame(Arrays.asList(rows.map((g, a, b) => Row(g, a, b))*), edgeSchema)

  private def labels(r: ConnectedComponents.Result): Map[(Int, String), String] =
    r.labels.collect().map(x => (x.getInt(0), x.getString(1)) -> x.getString(2)).toMap

  // Path n1 - n2 - ... - n7 (labels need 6 hops to travel from n1 to n7).
  private val path = (1 to 7).map(i => s"n$i")
  private val pathEdges = path.zip(path.tail).map((a, b) => (1, b, a))

  test("a path converges to its smallest node; iterations = diameter + 1 confirming round") {
    val r = ConnectedComponents.run(
      nodes(path.map(n => (1, n))*),
      edges(pathEdges*),
      Seq("g"),
      Vector(Vector(1)),
      maxIterations = 20,
      checkpointInterval = 2,
      checkpoint = true
    )
    assert(labels(r).values.toSet == Set("n1"))
    assert(r.groups == Vector(ConnectedComponents.GroupIterations(Vector(1), 7, converged = true)))
  }

  test("maxIterations reached before the fixed point: converged = false, partial labels only") {
    val r = ConnectedComponents.run(
      nodes(path.map(n => (1, n))*),
      edges(pathEdges*),
      Seq("g"),
      Vector(Vector(1)),
      maxIterations = 3,
      checkpointInterval = 1,
      checkpoint = true
    )
    assert(r.groups.head == ConnectedComponents.GroupIterations(Vector(1), 3, converged = false))
    val l = labels(r)
    assert(l((1, "n7")) == "n4") // the label has travelled 3 hops: never a wrong merge
    assert(l((1, "n1")) == "n1")
  }

  test("groups never mix; a group with no edges converges in one iteration; empty groups report") {
    val r = ConnectedComponents.run(
      nodes((1, "b"), (1, "c"), (2, "a"), (2, "b"), (2, "z")),
      edges((1, "b", "c"), (2, "z", "b")),
      Seq("g"),
      Vector(Vector(1), Vector(2), Vector(3)),
      maxIterations = 10,
      checkpointInterval = 5,
      checkpoint = false
    )
    assert(
      labels(r) == Map(
        (1, "b") -> "b",
        (1, "c") -> "b",
        (2, "a") -> "a",
        (2, "b") -> "b",
        (2, "z") -> "b"
      )
    )
    assert(r.groups.forall(_.converged))
    assert(r.groups.find(_.group == Vector(3)).get.iterations == 1)
  }
