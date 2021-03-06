package it.agilelab.darwin.connector.hbase

import com.typesafe.config.Config
import it.agilelab.darwin.common.{Connector, Logging}
import org.apache.avro.Schema
import org.apache.avro.Schema.Parser
import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory, Put, Result}
import org.apache.hadoop.hbase.security.User
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.hadoop.security.UserGroupInformation

import scala.collection.JavaConverters._


object HBaseConnector extends Logging {

  private var _instance: HBaseConnector = _

  def instance(hbaseConfig: Config): HBaseConnector = {
    synchronized {
      if (_instance == null) {
        log.debug("Initialization of HBase connector")
        _instance = HBaseConnector(hbaseConfig)
        log.debug("HBase connector initialized")
      }
    }
    _instance
  }
}

case class HBaseConnector(config: Config) extends Connector(config) with Logging {

  val DEFAULT_NAMESPACE: String = "AVRO"
  val DEFAULT_TABLENAME: String = "SCHEMA_REPOSITORY"

  val TABLE_NAME_STRING: String = if (config.hasPath(ConfigurationKeys.TABLE)) {
    config.getString(ConfigurationKeys.TABLE)
  } else {
    DEFAULT_TABLENAME
  }

  val NAMESPACE_STRING: String = if (config.hasPath(ConfigurationKeys.NAMESPACE)) {
    config.getString(ConfigurationKeys.NAMESPACE)
  } else {
    DEFAULT_NAMESPACE
  }

  lazy val TABLE_NAME: TableName = TableName.valueOf(Bytes.toBytes(NAMESPACE_STRING), Bytes.toBytes(TABLE_NAME_STRING))

  lazy val CF: Array[Byte] = Bytes.toBytes("0")
  lazy val QUALIFIER: Array[Byte] = Bytes.toBytes("schema")

  log.debug("Creating default HBaseConfiguration")
  val configuration: Configuration = HBaseConfiguration.create()
  log.debug("Created default HBaseConfiguration")

  if (config.hasPath(ConfigurationKeys.CORE_SITE) && config.hasPath(ConfigurationKeys.HBASE_SITE)) {
    log.debug(addResourceMessage(config.getString(ConfigurationKeys.CORE_SITE)))
    configuration.addResource(new Path(config.getString(ConfigurationKeys.CORE_SITE)))
    log.debug(addResourceMessage(config.getString(ConfigurationKeys.HBASE_SITE)))
    configuration.addResource(new Path(config.getString(ConfigurationKeys.HBASE_SITE)))
  }


  private def addResourceMessage(s: String) = {
    val ADDING_RESOURCE = "Adding resource: "
    ADDING_RESOURCE + s
  }

  val connection: Connection = if (config.getBoolean(ConfigurationKeys.IS_SECURE)) {
    log.debug(s"Calling UserGroupInformation.setConfiguration()")
    UserGroupInformation.setConfiguration(configuration)

    log.debug(s"Calling UserGroupInformation.loginUserFromKeytab(${config.getString(ConfigurationKeys.PRINCIPAL)}, " +
      s"${config.getString(ConfigurationKeys.KEYTAB_PATH)})")
    val ugi = UserGroupInformation.loginUserFromKeytabAndReturnUGI(
      config.getString(ConfigurationKeys.PRINCIPAL), config.getString(ConfigurationKeys.KEYTAB_PATH)
    )
    UserGroupInformation.setLoginUser(ugi)
    val user = User.create(ugi)
    log.trace(s"initialization of HBase connection with configuration:\n " +
      s"${configuration.iterator().asScala.map { entry => entry.getKey -> entry.getValue }.mkString("\n")}")
    ConnectionFactory.createConnection(configuration, user)
  } else {
    log.trace(s"initialization of HBase connection with configuration:\n " +
      s"${configuration.iterator().asScala.map { entry => entry.getKey -> entry.getValue }.mkString("\n")}")
    ConnectionFactory.createConnection(configuration)
  }

  log.debug("HBase connection initialized")
  sys.addShutdownHook {
    //  log.info(s"closing HBase connection pool")
    IOUtils.closeQuietly(connection)
  }

  //TODO this must be a def (a new Parser is created each time) because if the same Parser is used, it fails if you
  //TODO parse a class A and after it a class B that has a field of type A => ERROR: Can't redefine type A.
  //TODO Sadly the Schema.parse() method that would solve this problem is now deprecated
  private def parser: Parser = new Parser()

  override def fullLoad(): Seq[(Long, Schema)] = {
    log.debug(s"loading all schemas from table $NAMESPACE_STRING:$TABLE_NAME_STRING")
    val scanner: Iterable[Result] = connection.getTable(TABLE_NAME).getScanner(CF, QUALIFIER).asScala
    val schemas = scanner.map { result =>
      val key = Bytes.toLong(result.getRow)
      val value = Bytes.toString(result.getValue(CF, QUALIFIER))
      key -> parser.parse(value)
    }.toSeq
    log.debug(s"${schemas.size} loaded from HBase")
    schemas
  }

  override def insert(schemas: Seq[(Long, Schema)]): Unit = {
    log.debug(s"inserting ${schemas.size} schemas in HBase table $NAMESPACE_STRING:$TABLE_NAME_STRING")
    val mutator = connection.getBufferedMutator(TABLE_NAME)
    schemas.map { case (id, schema) =>
      val put = new Put(Bytes.toBytes(id))
      put.addColumn(CF, QUALIFIER, Bytes.toBytes(schema.toString))
      put
    }.foreach(mutator.mutate)
    mutator.flush()
    log.debug(s"insertion of schemas into $NAMESPACE_STRING:$TABLE_NAME_STRING successful")
  }
}



