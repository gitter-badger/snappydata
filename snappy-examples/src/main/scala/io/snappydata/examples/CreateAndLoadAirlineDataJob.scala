package io.snappydata.examples

import java.io.{File, PrintWriter}

import scala.util.{Failure, Success, Try}

import com.typesafe.config.Config
import org.apache.spark.sql.types.{StructType, StructField}
import org.apache.spark.sql.{SnappyContext, SaveMode, SnappySQLJob}
import spark.jobserver.{SparkJobInvalid, SparkJobValid, SparkJobValidation}

/**
  * Creates and loads Airline data from parquet files in row and column
  * tables. Also samples the data and stores it in a column table.
  */
object CreateAndLoadAirlineDataJob extends SnappySQLJob {

  var airlinefilePath: String = _
  var airlinereftablefilePath: String = _
  val colTable = "AIRLINE"
  val rowTable = "AIRLINEREF"
  val sampleTable = "AIRLINE_SAMPLE"
  val stagingAirline = "STAGING_AIRLINE"

  override def runJob(snc: SnappyContext, jobConfig: Config): Any = {
    def getCurrentDirectory = new java.io.File(".").getCanonicalPath
    val pw = new PrintWriter("CreateAndLoadAirlineDataJob.out")
    Try {
      // scalastyle:off println

      // Drop tables if already exists
      snc.dropTable(colTable, true)
      snc.dropTable(rowTable, true)
      snc.dropTable(sampleTable, true)
      snc.dropTable(stagingAirline, true)

      pw.println(s"****** CreateAndLoadAirlineDataJob ******")

      // Create a DF from the parquet data file and make it a table
      val airlineDF = snc.createExternalTable(stagingAirline, "parquet",
        Map("path" -> airlinefilePath))
      val updatedSchema = replaceReservedWords(airlineDF.schema)

      // Create a table in snappy store
      snc.createTable(colTable, "column",
        updatedSchema, Map("buckets" -> "5"))

      // Populate the table in snappy store
      airlineDF.write.mode(SaveMode.Append).saveAsTable(colTable)
      pw.println(s"Created and imported data in $colTable table.")

      // Create a DF from the airline ref data file
      val airlinerefDF = snc.read.load(airlinereftablefilePath)

      // Create a table in snappy store
      snc.createTable(rowTable, "row",
        airlinerefDF.schema, Map.empty[String, String])

      // Populate the table in snappy store
      airlinerefDF.write.mode(SaveMode.Append).saveAsTable(rowTable)

      pw.println(s"Created and imported data in $rowTable table")

      // Create a sample table sampling parameters.
      snc.createSampleTable(sampleTable, None,
        Map("buckets" -> "5",
          "qcs" -> "UniqueCarrier, Year_, Month_",
          "fraction" -> "0.03",
          "strataReservoirSize" -> "50",
          "basetable" -> "Airline"
        ))

      // Initiate the sampling from base table to sample table.
      snc.table(colTable).write.mode(SaveMode.Append).saveAsTable(sampleTable)

      pw.println(s"Created and imported data in $sampleTable table.")

      pw.println(s"****** Job finished ******")

    } match {
      case Success(v) => pw.close()
        s"See ${getCurrentDirectory}/CreateAndLoadAirlineDataJob.out"
      case Failure(e) => pw.close();
        throw e;
    }
    // scalastyle:on println
  }

  /**
    * Validate if the data files are available, else throw SparkJobInvalid
    *
    */
  override def validate(snc: SnappyContext, config: Config): SparkJobValidation = {

    airlinefilePath = if (config.hasPath("airline_file")) {
      config.getString("airline_file")
    } else {
      "../../quickstart/data/airlineParquetData"
    }

    if (!(new File(airlinefilePath)).exists()) {
      return new SparkJobInvalid("Incorrect airline path. " +
          "Specify airline_file property in APP_PROPS")
    }

    airlinereftablefilePath = if (config.hasPath("airlineref_file")) {
      config.getString("airlineref_file")
    } else {
      "../../quickstart/data/airportcodeParquetData"
    }
    if (!(new File(airlinereftablefilePath)).exists()) {
      return new SparkJobInvalid("Incorrect airline ref path. " +
          "Specify airlineref_file property in APP_PROPS")
    }

    SparkJobValid
  }

  /**
    * Replace the words that are reserved in Snappy store
    * @param airlineSchema schema with reserved words
    * @return updated schema
    */
  private def replaceReservedWords(airlineSchema : StructType) : StructType = {
    new StructType( airlineSchema.map( s => {
      if (s.name.equals("Year")) {
        new StructField("Year_", s.dataType, s.nullable, s.metadata)
      }
      else if (s.name.equals("Month")) {
        new StructField("Month_", s.dataType, s.nullable, s.metadata)
      }
      else {
        s
      }}).toArray)
  }
}