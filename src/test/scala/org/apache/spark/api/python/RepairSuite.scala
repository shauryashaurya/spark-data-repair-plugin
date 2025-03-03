/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.api.python

import org.json4s._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, Row}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

class RepairSuite extends QueryTest with SharedSparkSession {

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    spark.sql(s"SET ${SQLConf.CBO_ENABLED.key}=true")
  }

  private def resourcePath(f: String): String = {
    Thread.currentThread().getContextClassLoader.getResource(f).getPath
  }

  test("checkInputTable - type check") {
    Seq("BOOLEAN", "DATE", "TIMESTAMP", "ARRAY<INT>", "STRUCT<a: INT, b: DOUBLE>", "MAP<INT, INT>")
        .foreach { tpe =>
      withTable("t") {
        spark.sql(s"CREATE TABLE t(tid STRING, v1 STRING, v2 $tpe) USING parquet")
        val errMsg = intercept[AnalysisException] {
          RepairApi.checkInputTable("default", "t", "tid")
        }.getMessage
        assert(errMsg.contains("unsupported ones found"))
      }
    }
  }

  test("checkInputTable - #columns check") {
    Seq("tid STRING", "tid STRING, c1 INT").foreach { schema =>
      withTable("t") {
        spark.sql(s"CREATE TABLE t($schema) USING parquet")
        val errMsg = intercept[AnalysisException] {
          RepairApi.checkInputTable("default", "t", "tid")
        }.getMessage
        assert(errMsg.contains("A least three columns"))
      }
    }
  }

  test("checkInputTable - uniqueness check") {
    withTempView("t") {
      spark.range(100).selectExpr("1 AS tid", "id % 2 AS c0", " id % 3 AS c0")
        .createOrReplaceTempView("t")
      val errMsg = intercept[AnalysisException] {
        RepairApi.checkInputTable("", "t", "tid")
      }.getMessage
      assert(errMsg.contains("Uniqueness does not hold"))
    }
  }

  test("checkInputTable") {
    withTempView("t") {
      val supportedTypes = Seq("BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE", "STRING")
      val exprs = "CAST(id AS INT) tid" +:
        supportedTypes.zipWithIndex.map { case (t, i) => s"CAST(id AS $t) AS v$i" }
      spark.range(1).selectExpr(exprs: _*).createOrReplaceTempView("t")
      val jsonString = RepairApi.checkInputTable("", "t", "tid")
      val jsonObj = parse(jsonString)
      val data = jsonObj.asInstanceOf[JObject].values
      assert(data("input_table") === "t")
      assert(data("continous_attrs") === "v0,v1,v2,v3,v4,v5")
    }

  }

  test("withCurrentValues") {
    import testImplicits._
    Seq(("tid", "c0", "c1", "c2"), ("t i d", "c 0", "c 1", "c 2")).foreach { case (tid, c0, c1, c2) =>
      withTempView("inputView", "errCellView") {
        Seq(
          (1, 100, "abc", 1.2),
          (2, 200, "def", 3.2),
          (3, 300, "ghi", 2.1),
          (4, 400, "jkl", 1.9),
          (5, 500, "mno", 0.5)
        ).toDF(tid, c0, c1, c2).createOrReplaceTempView("inputView")

        Seq((2, c1), (2, c2), (3, c0), (5, c2))
          .toDF(tid, "attribute").createOrReplaceTempView("errCellView")

        val df = RepairApi.withCurrentValues("inputView", "errCellView", tid, s"$c0,$c1,$c2")
        checkAnswer(df, Seq(
          Row(2, s"$c1", "def"),
          Row(2, s"$c2", "3.2"),
          Row(3, s"$c0", "300"),
          Row(5, s"$c2", "0.5")
        ))
      }
    }
  }

  test("computeAndGetTableStats") {
    Seq(("v0", "v1", "v2", "v3"), ("v 0", "v 1", "v 2", "v 3")).foreach { case (v0, v1, v2, v3) =>
      withTempView("t") {
        spark.range(30).selectExpr(
          s"CAST(id % 2 AS BOOLEAN) AS `$v0`",
          s"CAST(id % 3 AS LONG) AS `$v1`",
          s"CAST(id % 8 AS DOUBLE) AS `$v2`",
          s"CAST(id % 6 AS STRING) AS `$v3`"
        ).createOrReplaceTempView("t")
        val statMap = RepairApi.computeAndGetTableStats("t")
        assert(statMap.mapValues(_.distinctCount) ===
          Map(s"$v0" -> 2, s"$v1" -> 3, s"$v2" -> 8, s"$v3" -> 6))
      }
    }
  }

  test("computeDomainSizes") {
    Seq(("v0", "v1", "v2", "v3"), ("v 0", "v 1", "v 2", "v 3")).foreach { case (v0, v1, v2, v3) =>
      withTempView("t") {
        spark.range(30).selectExpr(s"id % 3 AS `$v0`", s"id % 8 AS `$v1`", s"id % 6 AS `$v2`", s"id % 9 AS `$v3`")
          .createOrReplaceTempView("t")
        val jsonString = RepairApi.computeDomainSizes("t")
        val jsonObj = parse(jsonString)
        val data = jsonObj.asInstanceOf[JObject].values
        assert(data("domain_stats") === Map(s"$v0" -> 3, s"$v1" -> 8, s"$v2" -> 6, s"$v3" -> 9))
      }
    }
  }

  test("convertToDiscretizedTable") {
    withTable("adult") {
      val hospitalFilePath = resourcePath("hospital.csv")
      spark.read.option("header", true).format("csv").load(hospitalFilePath).write.saveAsTable("hospital")
      val jsonString = RepairApi.convertToDiscretizedTable("default.hospital", "tid", 20)
      val jsonObj = parse(jsonString)
      val data = jsonObj.asInstanceOf[JObject].values

      val discretizedTable = data("discretized_table").toString
      assert(discretizedTable.startsWith("discretized_table_"))
      val discretizedCols = spark.table(discretizedTable).columns
      assert(discretizedCols.toSet === Set("tid", "HospitalType", "EmergencyService", "State"))
      assert(data("domain_stats") === Map(
        "HospitalOwner" -> 28,
        "MeasureName" -> 63,
        "Address2" -> 0,
        "Condition" -> 28,
        "Address3" -> 0,
        "PhoneNumber" -> 72,
        "CountyName" -> 65,
        "ProviderNumber" -> 71,
        "HospitalName" -> 68,
        "Sample" -> 355,
        "HospitalType" -> 13,
        "EmergencyService" -> 6,
        "City" -> 72,
        "Score" -> 71,
        "ZipCode" -> 67,
        "Address1" -> 78,
        "State" -> 4,
        "Stateavg" -> 74,
        "MeasureCode" -> 56))
    }
  }

  test("convertToDiscretizedTable - escaped column names") {
    import testImplicits._
    withTempView("inputView", "errCellView") {
      Seq(
        (1, 100, "abc", 1.2),
        (2, 200, "def", 3.2),
        (3, 100, "def", 2.1),
        (4, 100, "abc", 1.9),
        (5, 200, "abc", 0.5)
      ).toDF("t i d", "c 0", "c 1", "c 2").createOrReplaceTempView("inputView")

      val jsonString = RepairApi.convertToDiscretizedTable("inputView", "t i d", 2)
      val jsonObj = parse(jsonString)
      val data = jsonObj.asInstanceOf[JObject].values

      val discretizedTable = data("discretized_table").toString
      assert(discretizedTable.startsWith("discretized_table_"))
      val discretizedCols = spark.table(discretizedTable).columns
      assert(discretizedCols.toSet === Set("t i d", "c 0", "c 1", "c 2"))
      assert(data("domain_stats") === Map(
        "c 0" -> 2,
        "c 1" -> 2,
        "c 2" -> 5))
    }
  }

  test("convertErrorCellsToNull") {
    import testImplicits._
    Seq(("tid", "c0", "c1", "c2"), ("t i d", "c 0", "c 1", "c 2")).foreach { case (tid, c0, c1, c2) =>
      withTempView("inputView", "errCellView") {
        Seq(
          (1, 100, "abc", 1.2),
          (2, 200, "def", 3.2),
          (3, 300, "ghi", 2.1),
          (4, 400, "jkl", 1.9),
          (5, 500, "mno", 0.5)
        ).toDF(tid, c0, c1, c2).createOrReplaceTempView("inputView")

        Seq((2, c1, "def"), (2, c2, "3.2"), (3, c0, "300"), (5, c2, "0.5"))
          .toDF(tid, "attribute", "current_value").createOrReplaceTempView("errCellView")

        val jsonString = RepairApi.convertErrorCellsToNull("inputView", "errCellView", tid, s"$c0,$c1,$c2")
        val jsonObj = parse(jsonString)
        val data = jsonObj.asInstanceOf[JObject].values

        val viewName = data("repair_base_cells").toString
        assert(viewName.startsWith("repair_base_cells_"))
        checkAnswer(spark.table(viewName), Seq(
          Row(1, 100, "abc", 1.2),
          Row(2, 200, null, null),
          Row(3, null, "ghi", 2.1),
          Row(4, 400, "jkl", 1.9),
          Row(5, 500, "mno", null)
        ))
      }
    }
  }

  test("computeFreqStats") {
    Seq(("tid", "xx", "yy"), ("t i d", "x x", "y y")).foreach { case (tid, x, y) =>
      withTempView("tempView") {
        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW tempView(`$tid`, `$x`, `$y`) AS SELECT * FROM VALUES
             |  (1, "1", "test-1"),
             |  (2, "2", "test-2"),
             |  (3, "3", "test-3"),
             |  (4, "2", "test-2"),
             |  (5, "1", "test-1"),
             |  (6, "1", "test-1"),
             |  (7, "3", "test-3"),
             |  (8, "3", "test-3"),
             |  (9, "2", "test-2a")
           """.stripMargin)

        val attrsToComputeFreqStats = Seq(Seq(x), Seq(y), Seq(x, y))
        val df1 = RepairApi.computeFreqStats("tempView", attrsToComputeFreqStats, 0.0)
        checkAnswer(df1, Seq(
          Row("1", 0, "test-1", 0, 3),
          Row("2", 0, "test-2a", 0, 1),
          Row(null, 1, "test-2a", 0, 1),
          Row("2", 0, "test-2", 0, 2),
          Row(null, 1, "test-2", 0, 2),
          Row("3", 0, null, 1, 3),
          Row("3", 0, "test-3", 0, 3),
          Row(null, 1, "test-1", 0, 3),
          Row("2", 0, null, 1, 3),
          Row(null, 1, "test-3", 0, 3),
          Row("1", 0, null, 1, 3)
        ))
        val df2 = RepairApi.computeFreqStats("tempView", attrsToComputeFreqStats, 0.3)
        checkAnswer(df2, Seq(
          Row("1", 0, "test-1", 0, 3),
          Row("3", 0, null, 1, 3),
          Row("3", 0, "test-3", 0, 3),
          Row(null, 1, "test-1", 0, 3),
          Row("2", 0, null, 1, 3),
          Row(null, 1, "test-3", 0, 3),
          Row("1", 0, null, 1, 3)
        ))

        val errMsg = intercept[IllegalStateException] {
          RepairApi.computeFreqStats("tempView", Seq(Seq(tid, x, y)), 0.0)
        }.getMessage
        assert(errMsg.contains(s"Cannot handle more than two entries: $tid,$x,$y"))
      }
    }
  }

  test("should support at least 63 attribute groups in computeFreqStats") {
    withTempView("t") {
      // TODO: If `WHOLESTAGE_CODEGEN_ENABLED` enabled, the Spark codegen mechanism fails
      withSQLConf((SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key, "false")) {
        def computeFreqStats(numColumns: Int): DataFrame = {
          val (genColumnExpr, columns) = (0 until numColumns).map { i => (s"id c$i", s"c$i") }.unzip
          val targetAttrSets = columns.combinations(2).toSeq
          spark.range(1).selectExpr(genColumnExpr: _*).createOrReplaceTempView("t")
          RepairApi.computeFreqStats("t", targetAttrSets, 0.0)
        }

        val df = computeFreqStats(64)
        assert(df.count() === 2016)
        checkAnswer(df.selectExpr("cnt").distinct(), Row(1))

        val errMsg = intercept[AnalysisException] {
          computeFreqStats(65)
        }.getMessage
        assert(errMsg.contains("Cannot handle the target attributes whose length " +
          "is more than 64, but got:"))
      }
    }
  }

  test("computePairwiseStats - no frequency stat") {
    withTempView("emptyFreqAttrStats") {
      spark.range(1).selectExpr(
          "id AS x",
          "0 __generated_freq_group_x",
          "id AS y",
          "0 __generated_freq_group_y",
          "id AS cnt")
        .where("1 == 0")
        .createOrReplaceTempView("emptyFreqAttrStats")

      val domainStatMap = Map("tid" -> 9L, "x" -> 2L, "y" -> 4L)
      val pairwiseStatMap = RepairApi.computePairwiseStats(
        1000, "emptyFreqAttrStats", Seq(("x", "y"), ("y", "x")), domainStatMap)
      assert(pairwiseStatMap.keySet === Set("x", "y"))
      assert(pairwiseStatMap("x").map(_._1) === Seq("y"))
      assert(pairwiseStatMap("x").head._2 === 1.0) // the worst-case value
      assert(pairwiseStatMap("y").map(_._1) === Seq("x"))
      assert(pairwiseStatMap("y").head._2 === 2.0) // the worst-case value
    }
  }

  test("computePairwiseStats") {
    Seq(("tid", "xx", "yy"), ("t i d", "x x", "y y")).foreach { case (tid, x, y) =>
      withTempView("freqAttrStats") {
        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW freqAttrStats(`$x`, `__generated_freq_group_$x`, `$y`, `__generated_freq_group_$y`, cnt) AS SELECT * FROM VALUES
             |  ("2", 0, "test-2", 0, 2),
             |  ("2", 0, "test-1", 0, 2),
             |  ("3", 0, "test-1", 0, 1),
             |  ("1", 0, "test-1", 0, 1),
             |  ("2", 0, "test-2a", 0, 1),
             |  ("3", 0, "test-3", 0, 2),
             |  (null, 1, "test-2", 0, 2),
             |  ("3", 0, null, 1, 3),
             |  ("2", 0, null, 1, 5),
             |  (null, 1, "test-1", 0, 4),
             |  (null, 1, "test-2a", 0, 1),
             |  (null, 1, "test-3", 0, 2),
             |  ("1", 0, null, 1, 1)
             """.stripMargin)

        val domainStatMap = Map(tid -> 9L, s"$x" -> 3L, s"$y" -> 4L)
        val pairwiseStatMap = RepairApi.computePairwiseStats(
          9, "freqAttrStats", Seq((s"$x", s"$y"), (s"$y", s"$x")), domainStatMap)
        assert(pairwiseStatMap.keySet === Set(s"$x", s"$y"))
        assert(pairwiseStatMap(s"$x").map(_._1) === Seq(s"$y"))
        assert(pairwiseStatMap(s"$x").head._2 > 0.0)
        assert(pairwiseStatMap(s"$y").map(_._1) === Seq(s"$x"))
        assert(pairwiseStatMap(s"$y").head._2 > 0.0)
      }
    }
  }

  test("computeAttrStats") {
    Seq(("tid", "xx", "yy"), ("t i d", "x x", "y y")).foreach { case (tid, x, y) =>
      withTempView("tempView", "attrStatView") {
        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW tempView(`$tid`, `$x`, `$y`) AS SELECT * FROM VALUES
             |  (1, "1", "test-1"),
             |  (2, "2", "test-2"),
             |  (3, "3", "test-3"),
             |  (4, "2", "test-2"),
             |  (5, "1", "test-1"),
             |  (6, "1", "test-1"),
             |  (7, "3", "test-3"),
             |  (8, "3", "test-3"),
             |  (9, "2", "test-2a")
           """.stripMargin)

        def computeAttrStats(freqAttrStatThreshold: Double): Map[String, Any] = {
          val domainStatMapAsJson = s"""{"$tid": 9,"$x": 3,"$y": 4}"""
          val jsonString = RepairApi.computeAttrStats(
            "tempView", tid, s"$x,$y", domainStatMapAsJson, freqAttrStatThreshold, 1.0, 256)
          val jsonObj = parse(jsonString)
          jsonObj.asInstanceOf[JObject].values
        }

        val data1 = computeAttrStats(0.0)
        checkAnswer(spark.table(data1("attr_freq_stats").toString), Seq(
          Row("1", 0, "test-1", 0, 3),
          Row("2", 0, "test-2a", 0, 1),
          Row(null, 1, "test-2a", 0, 1),
          Row("2", 0, "test-2", 0, 2),
          Row(null, 1, "test-2", 0, 2),
          Row("3", 0, null, 1, 3),
          Row("3", 0, "test-3", 0, 3),
          Row(null, 1, "test-1", 0, 3),
          Row("2", 0, null, 1, 3),
          Row(null, 1, "test-3", 0, 3),
          Row("1", 0, null, 1, 3)
        ))
        val pairwiseStatMap1 = data1("pairwise_attr_corr_stats")
          .asInstanceOf[Map[String, Seq[Seq[String]]]]
          .mapValues(_.map { case Seq(attr, sv) => (attr, sv.toDouble) })
        assert(pairwiseStatMap1.keySet === Set(s"$x", s"$y"))
        assert(pairwiseStatMap1(s"$x").head._1 === s"$y")
        assert(pairwiseStatMap1(s"$x").head._2 <= 1.0)
        assert(pairwiseStatMap1(s"$y").head._1 === s"$x")
        assert(pairwiseStatMap1(s"$y").head._2 <= 1.0)

        val data2 = computeAttrStats(1.0)
        checkAnswer(spark.table(data2("attr_freq_stats").toString), Nil)
        val pairwiseStatMap2 = data2("pairwise_attr_corr_stats")
          .asInstanceOf[Map[String, Seq[Seq[String]]]]
          .mapValues(_.map { case Seq(attr, sv) => (attr, sv.toDouble) })
        assert(pairwiseStatMap2.keySet === Set(s"$x", s"$y"))
        assert(pairwiseStatMap2(s"$x").head._1 === s"$y")
        assert(pairwiseStatMap1(s"$x").head._2 < pairwiseStatMap2(s"$x").head._2)
        assert(pairwiseStatMap2(s"$y").head._1 === s"$x")
        assert(pairwiseStatMap1(s"$y").head._2 < pairwiseStatMap2(s"$x").head._2)
      }
    }
  }

  test("computeDomainInErrorCells") {
    Seq(("tid", "xx", "yy", "zz"), ("t i d", "x x", "y y", "z z")).foreach { case (tid, x, y, z) =>
      withTempView("inputView", "errCellView", "freqAttrStats") {
        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW inputView(`$tid`, `$x`, `$y`, `$z`) AS SELECT * FROM VALUES
             |  (1, "2", "test-1", 1),
             |  (2, "2", "test-2", 1),
             |  (3, "3", "test-1", 3),
             |  (4, "2", "test-2", 2),
             |  (5, "1", "test-1", 1),
             |  (6, "2", "test-1", 1),
             |  (7, "3", "test-3", 2),
             |  (8, "3", "test-3", 3),
             |  (9, "2", "test-2a", 2)
           """.stripMargin)

        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW errCellView(`$tid`, attribute, current_value) AS SELECT * FROM VALUES
             |  (1, "$x", "2"),
             |  (3, "$y", "test-3"),
             |  (6, "$y", "test-2")
           """.stripMargin)

        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW freqAttrStats(`$x`, `__generated_freq_group_$x`, `$y`, `__generated_freq_group_$y`, `$z`, `__generated_freq_group_$z`, cnt) AS SELECT * FROM VALUES
             |  ("2", 0, null, 1, 1, 0, 3),
             |  (null, 1, "test-1", 0, 1, 0, 3),
             |  ("2", 0, null, 1, 2, 0, 2),
             |  ("2", 0, null, 1, null, 1, 5),
             |  (null, 1, "test-1", 0, null, 1, 4),
             |  ("2", 0, "test-2", 0, null, 1, 2),
             |  (null, 1, null, 1, 3, 0, 2),
             |  (null, 1, "test-1", 0, 3, 0, 1),
             |  (null, 1, "test-2", 0, 1, 0, 1),
             |  ("3", 0, null, 1, null, 1, 3),
             |  (null, 1, "test-2", 0, 2, 0, 1),
             |  (null, 1, null, 1, 0, 0, 4),
             |  ("2", 0, "test-1", 0, null, 1, 2),
             |  (null, 1, "test-2", 0, null, 1, 2),
             |  ("3", 0, "test-1", 0, null, 1, 1),
             |  (null, 1, null, 1, 2, 0, 3),
             |  ("3", 0, null, 1, 3, 0, 2),
             |  ("1", 0, "test-1", 0, null, 0, 1),
             |  (null, 1, "test-3", 0, 2, 0, 1),
             |  (null, 1, "test-3", 0, 3, 0, 1),
             |  (null, 1, "test-2a", 0, 2, 0, 1),
             |  ("1", 0, null, 1, null, 1, 1),
             |  ("3", 0, "test-3", 0, null, 1, 2),
             |  (null, 1, "test-2a", 0, null, 1, 1),
             |  ("2", 0, "test-2a", 0, null, 1, 1),
             |  (null, 1, "test-3", 0, null, 1, 2),
             |  ("3", 0, null, 1, 2, 0, 1),
             |  ("1", 0, null, 1, 1, 0, 1)
           """.stripMargin)

        val pairwiseStatMapAsJson = s"""{"$x": [["$y","1.0"]], "$y": [["$x","0.846950694324252"]]}"""
        val domainStatMapAsJson = s"""{"$tid": 9,"$x": 3,"$y": 4,"$z": 3}"""

        def testComputeDomain(domain_threshold_beta: Double, expected: Seq[Row]): Unit = {
          val domainDf = RepairApi.computeDomainInErrorCells(
            "inputView", "errCellView", tid, s"$z", s"$x,$y", "freqAttrStats", pairwiseStatMapAsJson, domainStatMapAsJson, 4, 0.0, domain_threshold_beta)
          assert(domainDf.columns.toSet === Set(tid, "attribute", "current_value", "domain"))
          val df = domainDf
            .selectExpr("*", "inline(domain)")
            .selectExpr(s"`$tid`", "attribute", "current_value", "n")
          checkAnswer(df, expected)
        }

        testComputeDomain(0.01, Seq(
          Row(1, s"$x", "2", "1"),
          Row(1, s"$x", "2", "2"),
          Row(1, s"$x", "2", "3"),
          Row(3, s"$y", "test-3", "test-1"),
          Row(3, s"$y", "test-3", "test-3"),
          Row(6, s"$y", "test-2", "test-1"),
          Row(6, s"$y", "test-2", "test-2"),
          Row(6, s"$y", "test-2", "test-2a")
        ))
      }
    }
  }

  test("repairByRegularExpression") {
    Seq(("tid", "xx", "yy"), ("t i d", "x x", "y y")).foreach { case (tid, x, y) =>
      withTempView("errCellView") {
        spark.sql(
          s"""
             |CREATE TEMPORARY VIEW errCellView(`$tid`, attribute, current_value) AS SELECT * FROM VALUES
             |  (1, "$x", "32 patxxnts"),
             |  (2, "$x", "1xx patients"),
             |  (3, "$x", null),
             |  (3, "$y", "yyy1"),
             |  (6, "$y", "yyy2")
           """.stripMargin)


        val df1 = RepairApi.repairByRegularExpression("^[0-9]{1,3} patients$", x, "errCellView", tid)
        checkAnswer(df1, Seq(
          Row(1, x, "32 patxxnts", "32 patients"),
          Row(2, x, "1xx patients", null),
          Row(3, x, null, null),
          Row(3, y, "yyy1", null),
          Row(6, y, "yyy2", null)
        ))

        val df2 = RepairApi.repairByRegularExpression("^[0-9]{1,", x, "errCellView", tid)
        checkAnswer(df2, Seq(
          Row(1, x, "32 patxxnts", null),
          Row(2, x, "1xx patients", null),
          Row(3, x, null, null),
          Row(3, y, "yyy1", null),
          Row(6, y, "yyy2", null)
        ))
      }
    }
  }
}
