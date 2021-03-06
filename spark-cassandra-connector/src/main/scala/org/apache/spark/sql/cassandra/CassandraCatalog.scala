package org.apache.spark.sql.cassandra

import java.io.IOException
import java.util.concurrent.TimeUnit

import com.datastax.spark.connector.cql.{CassandraConnector, Schema}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import org.apache.spark.sql.catalyst.analysis.Catalog
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Subquery}

private[cassandra] class CassandraCatalog(cc: CassandraSQLContext) extends Catalog {

  val caseSensitive: Boolean = true

  val schemas = CacheBuilder.newBuilder
       .maximumSize(100)
       .expireAfterWrite(cc.conf.getLong("schema.expire.in.minutes", 10), TimeUnit.MINUTES)
       .build(
          new CacheLoader[String, Schema] {
            def load(cluster: String) : Schema = {
              Schema.fromCassandra(CassandraConnector(cc.conf))
            }
          })

  override def lookupRelation(tableIdentifier: Seq[String], alias: Option[String]): LogicalPlan = {
    val (cluster, database, table) = getClusterDBTableNames(tableIdentifier)
    val schema = schemas.get(cluster)
    val keyspaceDef = schema.keyspaceByName.getOrElse(database, throw new IOException(s"Keyspace not found: $database"))
    val tableDef = keyspaceDef.tableByName.getOrElse(table, throw new IOException(s"Table not found: $database.$table"))
    val tableWithQualifiers = Subquery(table, CassandraRelation(tableDef, alias)(cc))
    alias.map(a => Subquery(a, tableWithQualifiers)).getOrElse(tableWithQualifiers)
  }

  private def getClusterDBTableNames(tableIdentifier: Seq[String]): (String, String, String) = {
    val id = processTableIdentifier(tableIdentifier).reverse.lift
    (id(2).getOrElse("default"), id(1).getOrElse(cc.getKeyspace), id(0).getOrElse(throw new IOException(s"Missing table name")))
  }

  override def registerTable(tableIdentifier: Seq[String], plan: LogicalPlan): Unit = ???

  override def unregisterTable(tableIdentifier: Seq[String]): Unit = ???

  override def unregisterAllTables(): Unit = ???

  override def tableExists(tableIdentifier: Seq[String]): Boolean = {
    val (cluster, database, table) = getClusterDBTableNames(tableIdentifier)
    val schema = schemas.get(cluster)
    val tabDef = for (ksDef <- schema.keyspaceByName.get(database); tabDef <- ksDef.tableByName.get(table)) yield tabDef
    tabDef.isDefined
  }
}
