package it.agilelab.darwin.common

import com.typesafe.config.Config
import org.apache.avro.Schema

/**
  * Generic abstraction of a component capable of reading and writing Schema entities in an external storage.
  * The external storage should keep at least the ID (Long) and the schema (Schema) for each entry.
  */
abstract class Connector(config: Config) extends Serializable {

  /**
    * Loads all schemas found on the storage
    * @return a sequence of all the pairs (ID, schema) found on the storage
    */
  def fullLoad(): Seq[(Long, Schema)]

  /**
    * Inserts all the schema passed as parameters in the storage.
    * This method is called when new schemas should be registered in the storage (the test if a schema is already in
    * the storage should be performed before the invocation of this method, e.g. by checking them against the
    * pre-loaded cache).
    *
    * @param schemas a sequence of pairs (ID, schema) Schema entities to insert in the storage.
    */
  def insert(schemas: Seq[(Long, Schema)]): Unit
}
