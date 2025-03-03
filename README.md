[![License](http://img.shields.io/:license-Apache_v2-blue.svg)](https://github.com/maropu/spark-data-repair-plugin/blob/master/LICENSE)
[![Build and test](https://github.com/maropu/spark-data-repair-plugin/workflows/Build%20and%20tests/badge.svg)](https://github.com/maropu/spark-data-repair-plugin/actions?query=workflow%3A%22Build+and+tests%22)
<!---
[![Coverage Status](https://coveralls.io/repos/github/maropu/spark-data-repair-plugin/badge.svg?branch=master)](https://coveralls.io/github/maropu/spark-data-repair-plugin?branch=master)
-->

This is an experimental prototype for building a statistical model to repair tabular data errors on [Apache Spark](https://spark.apache.org/)
which is a parallel and distributed framework for large-scale data processing.
Clean and consistent data is one of major interests for downstream analytics;
clean data makes machine learning and BI reporting more accurate and
consistent data with constraints (e.g., functional dependences) is important for efficient query plans.
Therefore, data repairing is a first step for a reliable analytics pipeline.

## How to Repair Error Cells

```
$ git clone https://github.com/maropu/spark-data-repair-plugin.git
$ cd spark-data-repair-plugin

# This repository includes a simple wrapper script `bin/python` to create
# a conda virtual environment to resolve the required dependencies
# (e.g., Python 3.7 and PySpark 3.2), and then
# launch a Python VM with our plugin.
$ ./bin/python

Welcome to
      ____              __
     / __/__  ___ _____/ /__
    _\ \/ _ \/ _ `/ __/  '_/
   /__ / .__/\_,_/_/ /_/\_\   version 3.2.0
      /_/

Using Python version 3.7.11 (default, Jul 27 2021 07:03:16)
SparkSession available as 'spark'.
Delphi APIs (version 0.1.0-spark3.2-EXPERIMENTAL) available as 'delphi'.

# Loads CSV data having seven NULL cells
>>> spark.read.option("header", True).csv("./testdata/adult.csv").createOrReplaceTempView("adult")
>>> spark.table("adult").show()
+---+-----+------------+-----------------+-------------+------+-------------+-----------+
|tid|  Age|   Education|       Occupation| Relationship|   Sex|      Country|     Income|
+---+-----+------------+-----------------+-------------+------+-------------+-----------+
|  0|31-50|Some-college|     Craft-repair|      Husband|  Male|United-States|LessThan50K|
|  1|  >50|Some-college|  Exec-managerial|    Own-child|Female|United-States|LessThan50K|
|  2|31-50|   Bachelors|            Sales|      Husband|  Male|United-States|LessThan50K|
|  3|22-30|     HS-grad|     Craft-repair|    Own-child|  null|United-States|LessThan50K|
|  4|22-30|     HS-grad|  Farming-fishing|      Husband|Female|United-States|LessThan50K|
|  5| null|Some-college|     Craft-repair|      Husband|  Male|United-States|       null|
|  6|31-50|     HS-grad|   Prof-specialty|Not-in-family|Female|United-States|LessThan50K|
|  7|31-50| Prof-school|   Prof-specialty|      Husband|  null|        India|MoreThan50K|
|  8|18-21|Some-college|     Adm-clerical|    Own-child|Female|United-States|LessThan50K|
|  9|  >50|     HS-grad|  Farming-fishing|      Husband|  Male|United-States|LessThan50K|
| 10|  >50|   Assoc-voc|   Prof-specialty|      Husband|  Male|United-States|LessThan50K|
| 11|  >50|     HS-grad|            Sales|      Husband|Female|United-States|MoreThan50K|
| 12| null|   Bachelors|  Exec-managerial|      Husband|  null|United-States|MoreThan50K|
| 13|22-30|     HS-grad|     Craft-repair|Not-in-family|  Male|United-States|LessThan50K|
| 14|31-50|  Assoc-acdm|  Exec-managerial|    Unmarried|  Male|United-States|LessThan50K|
| 15|22-30|Some-college|            Sales|    Own-child|  Male|United-States|LessThan50K|
| 16|  >50|Some-college|  Exec-managerial|    Unmarried|Female|United-States|       null|
| 17|31-50|     HS-grad|     Adm-clerical|Not-in-family|Female|United-States|LessThan50K|
| 18|31-50|        10th|Handlers-cleaners|      Husband|  Male|United-States|LessThan50K|
| 19|31-50|     HS-grad|            Sales|      Husband|  Male|         Iran|MoreThan50K|
+---+-----+------------+-----------------+-------------+------+-------------+-----------+

# Runs a job to compute repair updates for the seven NULL cells above in `dirty_df`
# A `repaired` column represents proposed updates to repiar them
>>> from repair.errors import NullErrorDetector
>>> repair_updates_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .setErrorDetectors([NullErrorDetector()]) \
...   .run()

>>> repair_updates_df.show()
+---+---------+-------------+-----------+
|tid|attribute|current_value|   repaired|
+---+---------+-------------+-----------+
|  7|      Sex|         null|     Female|
| 12|      Age|         null|      18-21|
| 12|      Sex|         null|     Female|
|  3|      Sex|         null|     Female|
|  5|      Age|         null|      18-21|
|  5|   Income|         null|MoreThan50K|
| 16|   Income|         null|MoreThan50K|
+---+---------+-------------+-----------+

# You need to set `True` to `repair_data` for getting repaired data directly
>>> clean_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .setErrorDetectors([NullErrorDetector()]) \
...   .run(repair_data=True)

>>> clean_df.show()
+---+-----+------------+-----------------+-------------+------+-------------+-----------+
|tid|  Age|   Education|       Occupation| Relationship|   Sex|      Country|     Income|
+---+-----+------------+-----------------+-------------+------+-------------+-----------+
|  0|31-50|Some-college|     Craft-repair|      Husband|  Male|United-States|LessThan50K|
|  1|  >50|Some-college|  Exec-managerial|    Own-child|Female|United-States|LessThan50K|
|  2|31-50|   Bachelors|            Sales|      Husband|  Male|United-States|LessThan50K|
|  3|22-30|     HS-grad|     Craft-repair|    Own-child|  Male|United-States|LessThan50K|
|  4|22-30|     HS-grad|  Farming-fishing|      Husband|Female|United-States|LessThan50K|
|  5|31-50|Some-college|     Craft-repair|      Husband|  Male|United-States|LessThan50K|
|  6|31-50|     HS-grad|   Prof-specialty|Not-in-family|Female|United-States|LessThan50K|
|  7|31-50| Prof-school|   Prof-specialty|      Husband|  Male|        India|MoreThan50K|
|  8|18-21|Some-college|     Adm-clerical|    Own-child|Female|United-States|LessThan50K|
|  9|  >50|     HS-grad|  Farming-fishing|      Husband|  Male|United-States|LessThan50K|
| 10|  >50|   Assoc-voc|   Prof-specialty|      Husband|  Male|United-States|LessThan50K|
| 11|  >50|     HS-grad|            Sales|      Husband|Female|United-States|MoreThan50K|
| 12|31-50|   Bachelors|  Exec-managerial|      Husband|  Male|United-States|MoreThan50K|
| 13|22-30|     HS-grad|     Craft-repair|Not-in-family|  Male|United-States|LessThan50K|
| 14|31-50|  Assoc-acdm|  Exec-managerial|    Unmarried|  Male|United-States|LessThan50K|
| 15|22-30|Some-college|            Sales|    Own-child|  Male|United-States|LessThan50K|
| 16|  >50|Some-college|  Exec-managerial|    Unmarried|Female|United-States|LessThan50K|
| 17|31-50|     HS-grad|     Adm-clerical|Not-in-family|Female|United-States|LessThan50K|
| 18|31-50|        10th|Handlers-cleaners|      Husband|  Male|United-States|LessThan50K|
| 19|31-50|     HS-grad|            Sales|      Husband|  Male|         Iran|MoreThan50K|
+---+-----+------------+-----------------+-------------+------+-------------+-----------+

# Or, you can merge the computed repair updates with the input table as follows
>>> repair_updates_df.createOrReplaceTempView("predicted")
>>> clean_df = delphi.misc.options({"repair_updates": "predicted", "table_name": "adult", "row_id": "tid"}).repair()
>>> clean_df.show()
<the same output above>
```

For more running examples, please check Python scripts in the [resources/examples](./resources/examples) folder.

NOTE: There are many types of errors on dirty data [9], but our purpose is to repair the data
whose attribute already has correct values against their errors.
For instance, in the `Sex` column in the `adult` table above, our plugin can repair the three NULL cells
because it already has correct values, `Female` or `Male`, against the NULL cells.
To repair them, our plugin captures and exploits data dependencies between the `Sex` column and the other ones.
For repairing the other types of data errors, existing data cleaning tools might be suitable;
a programming-by-examples technique is a good fit to fix format errors like `2021.8.23` -> `2021/8/23` and
[Trifacta](https://www.trifacta.com/) has a functionality,
named [Transformation by Example](https://docs.trifacta.com/display/SS/Transformation+by+Example+Page),
to implement it. Few existing tools can handle the error cases in the `adult` example above and,
therefore, our plugin is complementary to those other tools.

## Error Detection

To detect error cells, you can use some of bult-in error detectors below:

 - NullErrorDetector
 - DomainValues
 - RegExErrorDetector
 - ConstraintErrorDetector
 - GaussianOutlierErrorDetector
 - LOFOutlierErrorDetector

Please check [the example code](./resources/examples/error-detectors.py) for how to use these error detectors.
If you specify no error detector, `DomainValues`s for each attribute and `NullErrorDetector` are used by default.

```
# Setting `True` to `detect_errors_only` lets you get detected error cells only
>>> error_cells_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .setErrorDetectors([NullErrorDetector()]) \
...   .run(detect_errors_only=True)

>>> error_cells_df.show()
+---+---------+-------------+
|tid|attribute|current_value|
+---+---------+-------------+
| 12|      Age|         null|
|  5|      Age|         null|
| 12|      Sex|         null|
|  7|      Sex|         null|
|  3|      Sex|         null|
| 16|   Income|         null|
|  5|   Income|         null|
+---+---------+-------------+

# `DomainValue`s and `NullErrorDetector` are used by default
>>> error_cells_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .run(detect_errors_only=True)

>>> error_cells_df.show()
+---+----------+--------------+
|tid| attribute| current_value|
+---+----------+--------------+
| 12|       Age|          null|
|  5|       Age|          null|
|  7|       Sex|          null|
| 12|       Sex|          null|
|  3|       Sex|          null|
|  5|    Income|          null|
| 16|    Income|          null|
|  4|       Age|         22-30|
|  8|       Age|         18-21|
|  3|       Age|         22-30|
| 13|       Age|         22-30|
| 15|       Age|         22-30|
| 10| Education|     Assoc-voc|
|  7| Education|   Prof-school|
| 14| Education|    Assoc-acdm|
| 12| Education|     Bachelors|
|  2| Education|     Bachelors|
| 18| Education|          10th|
|  0|Occupation|  Craft-repair|
|  6|Occupation|Prof-specialty|
+---+----------+--------------+
only showing top 20 rows
```

Note that `ConstraintErrorDetector` is the most powerful choice; it uses [denial constraints](https://www.sciencedirect.com/science/article/pii/S0890540105000179) [5]
that an input tabular data should follow. The constraints consist of the predicates that cannot hold true simultaneously.

```
# Constraints below mean that `Sex="Female"` and `Relationship="Husband"`
# (`Sex="Male"` and `Relationship="Wife"`) does not hold true simultaneously.
# Note that the syntax for denial constraints follows the HoloClean [7] one and
# it is a research-backed statistical inference engine to clean data.
$ cat ./testdata/adult_constraints.txt
t1&EQ(t1.Sex,"Female")&EQ(t1.Relationship,"Husband")
t1&EQ(t1.Sex,"Male")&EQ(t1.Relationship,"Wife")

# Use the constraints to detect errors and then repair them
>>> repair_updates_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .setErrorDetectors([NullErrorDetector(), ConstraintErrorDetector(constraint_path="./testdata/adult_constraints.txt")]) \
...   .run()

# Changes values from `Female` to `Male` in the `Sex` cells
# of the 4th and 11th rows.
>>> repair_updates_df.show()
+---+------------+-------------+-----------+
|tid|   attribute|current_value|   repaired|
+---+------------+-------------+-----------+
|  3|         Sex|         null|       Male|
|  4|Relationship|      Husband|    Husband|
|  4|         Sex|       Female|       Male|
|  5|         Age|         null|      31-50|
|  5|      Income|         null|LessThan50K|
|  7|         Sex|         null|       Male|
| 11|Relationship|      Husband|    Husband|
| 11|         Sex|       Female|       Male|
| 12|         Age|         null|      31-50|
| 12|         Sex|         null|       Male|
| 16|      Income|         null|LessThan50K|
+---+------------+-------------+-----------+

# If the "adult" table has a functional dependency from "Age" to "Income",
# its dependency is represented as a following denial constraint:
>>> repair_updates_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .setErrorDetectors([ConstraintErrorDetector(constraints="t1&t2&EQ(t1.Age,t2.Age)&IQ(t1.Income,t2.Income)")]) \
...   .run()

# Or, you can use syntactic sugar instead
>>> repair_updates_df = delphi.repair \
...   .setInput("adult") \
...   .setRowId("tid") \
...   .setErrorDetectors([ConstraintErrorDetector(constraints="Age->Income")]) \
...   .run()
```

## Repairing based on Predicted Probabilities

If you want to select some of repaired updates based on theier probabilities, you can set `True` to
`compute_repair_prob` for getting the probabilities from built statistical models.

```
# To get predicted probabilities, computes repair updates with `compute_repair_prob`=`True`
>>> repair_updates_df = delphi.repair.setInput("adult").setRowId("tid").run(compute_repair_prob=True)
>>> repair_updates_df.show()
+---+---------+-------------+-----------+------------------+
|tid|attribute|current_value|   repaired|              prob|
+---+---------+-------------+-----------+------------------+
|  3|      Sex|         null|     Female|0.6664498420338913|
|  7|      Sex|         null|     Female|0.7436767447201434|
| 16|   Income|         null|MoreThan50K|0.8721610530603738|
|  5|      Age|         null|      18-21|0.3018171710707878|
|  5|   Income|         null|MoreThan50K|0.8333912988626406|
| 12|      Age|         null|      18-21|0.3598905853884847|
| 12|      Sex|         null|     Female|0.7436767447201434|
+---+---------+-------------+-----------+------------------+

# Applies the repair udpates whose probabilities are greater than 0.70
>>> repair_updates_df.where("prob > 0.70").createOrReplaceTempView("predicted")
>>> clean_df = delphi.misc.options({"repair_updates": "predicted", "table_name": "adult", "row_id": "tid"}).repair()
>>> clean_df.show()
<output with the four cells repaired>
```

## Run a Repair Job via spark-submit

You can run a repair job ([main.py](./python/main.py)) on your Spark cluster as following:

```
$ echo $SPARK_HOME
/tmp/spark-3.2.0-bin-hadoop3.2

$ ./bin/spark-submit ./python/main.py --input adult --output repaired --row-id tid
Predicted repair values are saved as 'repaired'

$ $SPARK_HOME/bin/spark-shell

scala> spark.table("repaired").show()
+---+---------+-------------+-----------+
|tid|attribute|current_value|   repaired|
+---+---------+-------------+-----------+
|  7|      Sex|         null|     Female|
| 12|      Age|         null|      18-21|
| 12|      Sex|         null|     Female|
|  3|      Sex|         null|     Female|
|  5|      Age|         null|      18-21|
|  5|   Income|         null|MoreThan50K|
| 16|   Income|         null|MoreThan50K|
+---+---------+-------------+-----------+
```

## Major Configurations

```
delphi.repair

  // Basic Parameters
  .setDbName(str)                              // database name (default: '')
  .setInput(str)                               // table name or `DataFrame`
  .setRowId(str)                               // unique column name in table
  .setTargets(list)                            // target attribute list to repair

  // Parameters for Error Detection
  .setErrorCells(str)                          // user-specified error cells
  .setErrorDetectors(list)                     // list of error detector implementations (`NullErrorDetector`, `DomainValues`, `RegExErrorDetector`, `ConstraintErrorDetector`, and `GaussianOutlierErrorDetector`)
  .setDiscreteThreshold(int)                   // max domain size of discrete values (default: 80)

  // Parameters for Repair Model Training
  .setRepairByRules(bool)                      // whether to enable rule-based repair techniques, e.g., using functional dependencies and merging nearest values (default: False)
  .setParallelStatTrainingEnabled(bool)        // whether to run multiples tasks to build stat repair models (default: False)
  .setTrainingDataRebalancingEnabled(bool)     // whether to rebalance class labels in training data (default: False)

  // Parameters for Repairing
  .setRepairDelta(int)                         // max number of applied repairs

  // Running Mode Parameters
  .run(
    detect_errors_only=bool,                   // whether to return detected error cells (default: False)
    compute_repair_candidate_prob=bool,        // whether to return probabiity mass function of candidate repairs (default: False)
    compute_repair_prob=bool,                  // whether to return probabiity of predicted repairs
    repair_data=bool                           // whether to return repaired data
  )
```

## References

 - [1] Heidari, Alireza et al., HoloDetect: Few-Shot Learning for Error Detection, Proceedings of SIGMOD, 2019.
 - [2] Mohamed Yakout et. al., Don't be SCAREd: use SCalable Automatic REpairing with maximal likelihood and bounded changes, Proceedings of SIGMOD, 2013.
 - [3] Ihab F. Ilyas and Xu Chu, Data Cleaning, ACM Books, 2019.
 - [4] Theodoros Rekatsinas et al., Holoclean: Holistic Data Repairs with Probabilistic Inference, PVLDB 10, no.11, pp.1190-1201, 2017.
 - [5] Jan Chomicki and Jerzy Marcinkowski, Minimal-Change Integrity Maintenance Using TupleDdeletions, Inf. Comput. 197(1-2), pp.90–121, 2005.
 - [6] Eduardo H. M. Pena et al., Discovery of Approximate (and Exact) Denial Constraints. Proceedings of the VLDB Endowment. 13(3), pp.266–278, 2019.
 - [7] Wu, Richard et al., Attention-based Learning for Missing Data Imputation in HoloClean, MLSys, 2020.
 - [8] Michael Stonebraker et al., Data Curation at Scale: The Data Tamer System, CIDR, 2013.
 - [9] Ziawasch Abedjan et al., Detecting Data Errors: Where Are We and What Needs to be Done?, Proceedings of the VLDB Endowment, 9(12), pp.993–1004, 2016.
 - [10] Zuhair Khayyat et al., BigDansing: A System for Big Data Cleansing, Proceedings of SIGMOD, pp.1215–1230, 2015.
 - [11] George Papadakis, et al., Blocking and Filtering Techniques for Entity Resolution, ACM Computing Surveys, Article 31, pp.42, 2020.
 - [12] Ahmed K. Elmagarmid et al., Duplicate Record Detection: A Survey, IEEE Transactions on Knowledge and Data Engineering, vol.19, no.1, pp.1-16, 2007.
 - [13] Ihab F. Ilyas and Xu Chu, Trends in Cleaning Relational Data: Consistency and Deduplication, Foundations and Trends in Databases, vol.5, no.4, pp.281-393, 2015.
 - [14] Mohamed Yakout et al., Guided data repair, Proceedings of the VLDB Endowment, 4(5), pp.279–289, 2011.
 - [15] El Kindi Rezig et al., Horizon: Scalable Dependency-driven Data Cleaning, Proceedings of the VLDB Endowment, vol.14, no.11, 2021.
 - [16] Peng Li et al., CleanML: A Study for Evaluating the Impact of Data Cleaning on ML Classification Tasks, Proceedings of ICDE, pp.13-24, 2021.
 - [17] Zeyu Li et al., Repairing data through regular expressions, Proceedings of the VLDB Endowment, vol.9, no.5, pp.432-443, 2016.
 - [18] Leopoldo Bertossi, Database Repairing and Consistent Query Answering, Synthesis Lectures on Data Management, Morgan & Claypool Publishers, 2011.
 - [19] Babak Salimi et al., Interventional Fairness: Causal Database Repair for Algorithmic Fairness, Proceedings of SIGMOD, pp.793–810, 2019.

## TODO

 - Implements a rule-based repair strategy using regular expressions (See [17])

## Bug Reports

If you hit some bugs and have requests, please leave some comments on [Issues](https://github.com/maropu/spark-data-repair-plugin/issues)
or Twitter ([@maropu](http://twitter.com/#!/maropu)).

