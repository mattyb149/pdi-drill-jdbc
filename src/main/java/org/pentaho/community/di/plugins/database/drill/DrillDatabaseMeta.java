package org.pentaho.community.di.plugins.database.drill;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.database.BaseDatabaseMeta;
import org.pentaho.di.core.database.DatabaseInterface;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.plugins.DatabaseMetaPlugin;
import org.pentaho.di.core.row.ValueMetaInterface;

import java.util.Arrays;
import java.util.List;

@DatabaseMetaPlugin( type = "drill", typeDescription = "Apache Drill" )
public class DrillDatabaseMeta extends BaseDatabaseMeta implements DatabaseInterface {

  private static final List<String> sysTables =
    Arrays.asList(
      "CATALOGS",
      "COLUMNS",
      "SCHEMATA",
      "TABLES",
      "VIEWS",
      "boot",
      "drillbits",
      "memory",
      "options",
      "threads",
      "version");

  @Override
  public int[] getAccessTypeList () {
    return new int[]{DatabaseMeta.TYPE_ACCESS_NATIVE, DatabaseMeta.TYPE_ACCESS_JNDI};
  }

  @Override
  public int getDefaultDatabasePort () {
    return -1;
  }

  @Override
  public String getDriverClass () {
    return org.pentaho.community.di.plugins.database.drill.delegate.DelegateDriver.class.getCanonicalName();

  }

  @Override
  public String getURL ( String hostname, String port, String databaseName ) {
    return "jdbc:drill:zk=" + hostname
          + (!Const.isEmpty ( port ) ? ":" + port : "")
          + (!Const.isEmpty ( databaseName ) ? ";schema=" + databaseName : "");

  }

  @Override
  public String getAddColumnStatement ( String arg0, ValueMetaInterface arg1, String arg2, boolean arg3, String arg4,
                                        boolean arg5 ) {
    return null;
  }

  @Override
  public String getFieldDefinition ( ValueMetaInterface v, String tk, String pk, boolean use_autoinc,
                                     boolean add_fieldname, boolean add_cr ) {
    String retval = "";

    String fieldname = v.getName ();
    int length = v.getLength ();
    int precision = v.getPrecision ();

    if ( add_fieldname ) {
      retval += fieldname + " ";
    }

    int type = v.getType ();
    switch ( type ) {
      case ValueMetaInterface.TYPE_DATE:
        retval += "TIMESTAMP";
        break;
      case ValueMetaInterface.TYPE_BOOLEAN:
        if ( supportsBooleanDataType () ) {
          retval += "BOOLEAN";
        } else {
          retval += "CHAR(1)";
        }
        break;
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_INTEGER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
        if ( fieldname.equalsIgnoreCase ( tk ) || // Technical key
              fieldname.equalsIgnoreCase ( pk ) // Primary key
              ) {
          retval += "BIGSERIAL";
        } else {
          if ( length > 0 ) {
            if ( precision > 0 || length > 18 ) {
              // Numeric(Precision, Scale): Precision = total length; Scale = decimal places
              retval += "NUMERIC(" + (length + precision) + ", " + precision + ")";
            } else {
              if ( length > 9 ) {
                retval += "BIGINT";
              } else {
                if ( length < 5 ) {
                  retval += "SMALLINT";
                } else {
                  retval += "INTEGER";
                }
              }
            }

          } else {
            retval += "DOUBLE PRECISION";
          }
        }
        break;
      case ValueMetaInterface.TYPE_STRING:
        if ( length < 1 || length >= DatabaseMeta.CLOB_LENGTH ) {
          retval += "TEXT";
        } else {
          retval += "VARCHAR(" + length + ")";
        }
        break;
      default:
        retval += " UNKNOWN";
        break;
    }

    if ( add_cr ) {
      retval += Const.CR;
    }

    return retval;
  }

  @Override
  public String getModifyColumnStatement ( String arg0, ValueMetaInterface arg1, String arg2, boolean arg3, String arg4,
                                           boolean arg5 ) {
    return null;
  }

  @Override
  public String[] getUsedLibraries () {
    return null;
  }

  /**
   * @return The start quote sequence, mostly just double quote, but sometimes [, ...
   */
  @Override
  public String getStartQuote() {
    return "`";
  }

  /**
   * @return The end quote sequence, mostly just double quote, but sometimes ], ...
   */
  @Override
  public String getEndQuote() {
    return "`";
  }

  /**
   * @return an array of reserved words for the database type...
   */
  @Override
  public String[] getReservedWords() {
    return new String[] { "TABLES" };
  }

  /**
   * @param tableName
   * @return true if the specified table is a system table
   */
  @Override
  public boolean isSystemTable( String tableName ) {
    return tableName != null && sysTables.contains( tableName );
  }
}
