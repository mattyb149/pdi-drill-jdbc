/*******************************************************************************
 *
 * Pentaho Big Data
 *
 * Copyright (C) 2002-2013 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.plugins.database.drill;

import org.apache.drill.jdbc.DrillResultSet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * DriverProxyInvocationChain is a temporary solution for interacting with Hive drivers. At the time this class was
 * added, many methods from the JDBC API had not yet been implemented and would instead throw SQLExceptions. Also, some
 * methods such as HiveDatabaseMetaData.getTables() did not return any values.  For these reasons, a dynamic proxy chain
 * was put in place, in order to intercept methods that would otherwise not function properly, and instead inject
 * working and/or default functionality.
 * <p/>
 * The "chain" part of this class is a result of not having access to all the necessary objects at driver creation time.
 * For this reason, we have to intercept methods that would return such objects, then create and return a proxy to those
 * objects. There are a number of objects and methods to which this applies, so the result is a "chain" of getting
 * access to objects via a proxy, then returning a proxy to those objects, which in turn may return proxied objects for
 * its methods, and so on.
 * <p/>
 * The large amount of reflection used here is because not all Hadoop distributions support both Hive and Hive 2. Thus
 * before proxying or anything, we need to make sure we have the classes we need at runtime.
 */
public class DriverProxyInvocationChain {

  /**
   * The initialized.
   */
  private static boolean initialized = false;


  protected static ClassLoader driverProxyClassLoader = null;

  /**
   * Gets the proxy.
   *
   * @param intf the interface to proxy
   * @param obj  the real object to create a proxy for
   * @return the proxy of the object's implementation of the interface
   */
  public static Driver getProxy ( Class<? extends Driver> intf, final Driver obj ) {
    driverProxyClassLoader = obj.getClass ().getClassLoader ();
    if ( !initialized ) {
      init ();
    }

    return (Driver) Proxy.newProxyInstance ( driverProxyClassLoader, new Class[]{intf},
          new DriverInvocationHandler ( obj ) );
  }

  /**
   * Initializes the Driver proxy chain
   */
  protected static void init () {

    initialized = true;
  }

  /**
   * DriverInvocationHandler is a proxy handler class for java.sql.Driver. However the code in this file is specifically
   * for handling Hive JDBC calls, and therefore should not be used to proxy any other JDBC objects besides those
   * provided by Hive.
   */
  private static class DriverInvocationHandler implements InvocationHandler {

    /**
     * The real driver object
     */
    Driver driver;

    /**
     * Instantiates a new Driver proxy handler.
     *
     * @param obj the Driver to proxy
     */
    public DriverInvocationHandler ( Driver obj ) {
      driver = obj;
    }

    /**
     * Intercepts methods called on the Driver to possibly perform alternate processing.
     *
     * @param proxy  the proxy object
     * @param method the method being invoked
     * @param args   the arguments to the method
     * @return the object returned by whatever processing takes place
     * @throws Throwable if an error occurs during processing
     */
    @Override
    public Object invoke ( final Object proxy, Method method, Object[] args ) throws Throwable {

      try {
        Object o = method.invoke ( driver, args );
        if ( o instanceof Connection ) {

          // Intercept the Connection object so we can proxy that too
          return Proxy.newProxyInstance ( o.getClass ().getClassLoader (),
                new Class[]{Connection.class}, new ConnectionInvocationHandler ( (Connection) o ) );
        } else {
          return o;
        }
      } catch ( Throwable t ) {
        throw (t instanceof InvocationTargetException) ? t.getCause () : t;
      }
    }
  }

  /**
   * ConnectionInvocationHandler is a proxy handler class for java.sql.Connection. However the code in this file is
   * specifically for handling Apache Drill JDBC calls, and therefore should not be used to proxy any other JDBC
   * objects besides those provided by Drill.
   */
  private static class ConnectionInvocationHandler implements InvocationHandler {

    /**
     * The real connection object
     */
    Connection connection;

    /**
     * Instantiates a new connection invocation handler.
     *
     * @param obj the obj
     */
    public ConnectionInvocationHandler ( Connection obj ) {
      connection = obj;
    }

    /**
     * Intercepts methods called on the Connection to possibly perform alternate processing.
     *
     * @param proxy  the proxy
     * @param method the method to be invoked
     * @param args   the args
     * @return the object
     * @throws Throwable the throwable
     */
    @Override
    public Object invoke ( Object proxy, Method method, Object[] args ) throws Throwable {
      Object o;
      try {
        o = method.invoke ( connection, args );
      } catch ( Throwable t ) {

        if ( t instanceof InvocationTargetException ) {
          Throwable cause = t.getCause ();

          if ( cause instanceof SQLException ) {
            if ( cause.getMessage ().equals ( "Method not supported" ) ) {
              String methodName = method.getName ();
              if ( "createStatement".equals ( methodName ) ) {
                o = createStatement ( connection, args );
              } else if ( "isReadOnly".equals ( methodName ) ) {
                o = Boolean.FALSE;
              } else if ( "setReadOnly".equals ( methodName ) ) {
                o = null;
              } else if ( "setAutoCommit".equals ( methodName ) ) {
                o = null;
              } else {
                throw cause;
              }
            } else {
              throw cause;
            }
          } else {
            throw cause;
          }
        } else {
          throw t;
        }

      }
      if ( o instanceof DatabaseMetaData ) {
        DatabaseMetaData dbmd = (DatabaseMetaData) o;

        // Intercept the DatabaseMetaData object so we can proxy that too
        return Proxy.newProxyInstance ( dbmd.getClass ().getClassLoader (),
              new Class[]{DatabaseMetaData.class}, new DatabaseMetaDataInvocationHandler ( dbmd, this ) );
      } else if ( o instanceof PreparedStatement ) {
        PreparedStatement st = (PreparedStatement) o;

        // Intercept the Statement object so we can proxy that too
        return Proxy.newProxyInstance ( st.getClass ().getClassLoader (),
              new Class[]{PreparedStatement.class}, new CaptureResultSetInvocationHandler<PreparedStatement> ( st ) );
      } else if ( o instanceof Statement ) {
        Statement st = (Statement) o;

        // Intercept the Statement object so we can proxy that too
        return Proxy.newProxyInstance ( st.getClass ().getClassLoader (),
              new Class[]{Statement.class}, new CaptureResultSetInvocationHandler<Statement> ( st ) );
      } else {
        return o;
      }
    }

    /**
     * Creates a statement for the given Connection with the specified arguments
     *
     * @param c    the connection object
     * @param args the arguments
     * @return the statement
     * @throws SQLException the sQL exception
     * @see java.sql.Connection#createStatement(int, int)
     */
    public Statement createStatement ( Connection c, Object[] args ) throws SQLException {
      if ( c.isClosed () ) {
        throw new SQLException ( "Can't create Statement, connection is closed " );
      }

      return c.createStatement ();
    }
  }

  /**
   * DatabaseMetaDataInvocationHandler is a proxy handler class for java.sql.DatabaseMetaData. However the code in this
   * file is specifically for handling Hive JDBC calls, and therefore should not be used to proxy any other JDBC objects
   * besides those provided by Hive.
   */
  private static class DatabaseMetaDataInvocationHandler implements InvocationHandler {

    /**
     * The "real" database metadata object.
     */
    DatabaseMetaData t;

    /**
     * The connection proxy associated with the DatabaseMetaData object
     */
    ConnectionInvocationHandler c;

    /**
     * Instantiates a new database meta data invocation handler.
     *
     * @param t the database metadata object to proxy
     */
    public DatabaseMetaDataInvocationHandler ( DatabaseMetaData t, ConnectionInvocationHandler c ) {
      this.t = t;
      this.c = c;
    }

    /**
     * Intercepts methods called on the DatabaseMetaData object to possibly perform alternate processing.
     *
     * @param proxy  the proxy
     * @param method the method
     * @param args   the args
     * @return the object
     * @throws Throwable the throwable
     */
    @Override
    public Object invoke ( Object proxy, Method method, Object[] args ) throws Throwable {

      try {
        String methodName = method.getName ();
        if ( "getConnection".equals ( methodName ) ) {
          // Return the connection
          return c;
        } else if ( "getIdentifierQuoteString".equals ( methodName ) ) {
          // Need to intercept getIdentifierQuoteString() before trying the driver version, as our "fixed"
          // drivers return a single quote when it should be empty.
          // TODO can we remove this?
          return getIdentifierQuoteString ();
        }

        // try to invoke the method as-is
        Object o = method.invoke ( t, args );
        if ( o instanceof ResultSet ) {
          ResultSet r = (ResultSet) o;

          return Proxy.newProxyInstance ( r.getClass ().getClassLoader (),
                new Class[]{ResultSet.class}, new ResultSetInvocationHandler ( r ) );
        } else {
          return o;
        }
      } catch ( Throwable t ) {
        if ( t instanceof InvocationTargetException ) {
          Throwable cause = t.getCause ();
          throw cause;
        } else {
          throw t;
        }
      }
    }

    /**
     * Returns the identifier quote string.
     *
     * @return String the quote string for identifiers in HiveQL
     * @throws SQLException if any SQL error occurs
     */
    public String getIdentifierQuoteString () throws SQLException {
      return "";
    }

  }

  /**
   * CaptureResultSetInvocationHandler is a generic proxy handler class for any java.sql.* class that has methods to
   * return ResultSet objects. However the code in this file is specifically for handling Hive JDBC calls, and therefore
   * should not be used to proxy any other JDBC objects besides those provided by Hive.
   *
   * @param <T> the generic type of object whose methods return ResultSet objects
   */
  private static class CaptureResultSetInvocationHandler<T extends Statement> implements InvocationHandler {

    /**
     * The object whose methods return ResultSet objects.
     */
    T t;

    /**
     * Instantiates a new capture result set invocation handler.
     *
     * @param t the t
     */
    public CaptureResultSetInvocationHandler ( T t ) {
      this.t = t;
    }

    /**
     * Intercepts methods called on the object to possibly perform alternate processing.
     *
     * @param proxy  the proxy
     * @param method the method
     * @param args   the args
     * @return the object
     * @throws Throwable the throwable
     */
    @Override
    public Object invoke ( Object proxy, Method method, Object[] args ) throws Throwable {
      // try to invoke the method as-is
      try {
        return getProxiedObject ( method.invoke ( t, args ) );
      } catch ( InvocationTargetException ite ) {
        Throwable cause = ite.getCause ();

        if ( cause instanceof SQLException ) {
          if ( cause.getMessage ().equals ( "Method not supported" ) ) {
            String methodName = method.getName ();
            // Intercept PreparedStatement.getMetaData() to see if it throws an exception
            if ( "getMetaData".equals ( methodName ) && (args == null || args.length == 0) ) {
              return getProxiedObject ( getMetaData () );
            } else if ( PreparedStatement.class.isInstance ( proxy ) && "setObject".equals ( methodName )
                  && args.length == 2 && Integer.class.isInstance ( args[0] ) ) {
              // Intercept PreparedStatement.setObject(position, value)
              // Set value using value type instead
              // TODO do we need this? Or does Drill insulate us from Hive (specifically older Hive versions)?
              PreparedStatement ps = (PreparedStatement) proxy;
              int parameterIndex = (Integer) args[0];
              Object x = args[1];

              if ( x == null ) {
                // PreparedStatement.setNull may not be supported
                ps.setNull ( parameterIndex, Types.NULL );
              } else if ( x instanceof String ) {
                ps.setString ( parameterIndex, (String) x );
              } else if ( x instanceof Short ) {
                ps.setShort ( parameterIndex, ((Short) x).shortValue () );
              } else if ( x instanceof Integer ) {
                ps.setInt ( parameterIndex, ((Integer) x).intValue () );
              } else if ( x instanceof Long ) {
                ps.setLong ( parameterIndex, ((Long) x).longValue () );
              } else if ( x instanceof Float ) {
                ps.setFloat ( parameterIndex, ((Float) x).floatValue () );
              } else if ( x instanceof Double ) {
                ps.setDouble ( parameterIndex, ((Double) x).doubleValue () );
              } else if ( x instanceof Boolean ) {
                ps.setBoolean ( parameterIndex, ((Boolean) x).booleanValue () );
              } else if ( x instanceof Byte ) {
                ps.setByte ( parameterIndex, ((Byte) x).byteValue () );
              } else if ( x instanceof Character ) {
                ps.setString ( parameterIndex, x.toString () );
              } else {
                // Can't infer a type.
                throw new SQLException ( "Type " + x.getClass () + " is not yet supported", cause );
              }
              return null;
            } else if ( PreparedStatement.class.isInstance ( proxy ) && "setNull".equals ( methodName )
                  && args.length == 2 && Integer.class.isInstance ( args[0] ) ) {

              PreparedStatement ps = (PreparedStatement) proxy;
              int parameterIndex = (Integer) args[0];
              // Use empty String instead (not ideal, but won't crash)
              ps.setString ( parameterIndex, "" );

              return null;
            } else {
              throw cause;
            }
          } else {
            throw cause;
          }
        } else {
          throw cause;
        }
      }
    }

    /**
     * Returns the result set meta data.  If a result set was not created by running an execute or executeQuery then a
     * null is returned.
     *
     * @return null is returned if the result set is null
     * @throws SQLException if an error occurs while getting metadata
     * @see java.sql.PreparedStatement#getMetaData()
     */

    public ResultSetMetaData getMetaData () {
      ResultSetMetaData rsmd = null;
      if ( t instanceof Statement ) {
        try {
          ResultSet resultSet = ((Statement) t).getResultSet ();
          rsmd = (resultSet == null ? null : resultSet.getMetaData ());
        } catch ( SQLException se ) {
          rsmd = null;
        }
      }
      return rsmd;
    }

    private Object getProxiedObject ( Object o ) {
      if ( o == null ) {
        return null;
      }

      if ( o instanceof ResultSet ) {
        ResultSet r = (ResultSet) o;

        return Proxy.newProxyInstance ( r.getClass ().getClassLoader (),
              new Class[]{ResultSet.class}, new ResultSetInvocationHandler ( r, t ) );
      } else if ( o instanceof ResultSetMetaData ) {
        ResultSetMetaData r = (ResultSetMetaData) o;

        return Proxy.newProxyInstance ( r.getClass ().getClassLoader (),
              new Class[]{ResultSetMetaData.class}, new ResultSetMetaDataInvocationHandler ( r ) );
      } else {
        return o;
      }
    }
  }

  /**
   * ResultSetInvocationHandler is a proxy handler class for java.sql.ResultSet. However the code in this file is
   * specifically for handling Drill JDBC calls, and therefore should not be used to proxy any other JDBC
   * objects besides those provided by Drill.
   */
  private static class ResultSetInvocationHandler implements InvocationHandler {

    /**
     * The "real" ResultSet object .
     */
    ResultSet rs;
    Statement st;

    /**
     * Instantiates a new result set invocation handler.
     *
     * @param r the r
     */
    public ResultSetInvocationHandler ( ResultSet r ) {
      rs = r;
    }

    /**
     * Instantiates a new result set invocation handler.
     *
     * @param r the r
     */
    public ResultSetInvocationHandler ( ResultSet r, Statement s ) {
      rs = r;
      st = s;
    }

    /**
     * Intercepts methods called on the ResultSet to possibly perform alternate processing.
     *
     * @param proxy  the proxy
     * @param method the method
     * @param args   the args
     * @return the object
     * @throws Throwable the throwable
     */
    @Override
    public Object invoke ( Object proxy, Method method, Object[] args ) throws Throwable {

      try {
        String methodName = method.getName ();

        // Intercept the getString(String) method to implement the hack for "show tables" vs. getTables()
        if ( "getString".equals ( methodName ) && args != null && args.length == 1 && args[0] instanceof String ) {
          return getString ( (String) args[0] );
        } else if ( "getType".equals ( methodName ) ) {
          // Return TYPE_FORWARD_ONLY (scrollability is not really supported)
          return ResultSet.TYPE_FORWARD_ONLY;
        } else {
          Object o = method.invoke ( rs, args );

          if ( o instanceof ResultSetMetaData ) {
            // Intercept the ResultSetMetaData object so we can proxy that too
            return (ResultSetMetaData) Proxy.newProxyInstance ( o.getClass ().getClassLoader (),
                  new Class[]{ResultSetMetaData.class},
                  new ResultSetMetaDataInvocationHandler ( (ResultSetMetaData) o ) );
          } else {
            return o;
          }
        }
      } catch ( Throwable t ) {

        if ( t instanceof InvocationTargetException ) {
          Throwable cause = t.getCause ();
          String methodName = method.getName ();

          if ( cause instanceof SQLException ) {
            if ( cause.getMessage ().equals ( "Method not supported" ) ) {
              if ( "getStatement".equals ( methodName ) ) {
                return getStatement ();
              } else {
                throw cause;
              }
            } else {
              throw cause;
            }
          } else if ( cause instanceof IllegalMonitorStateException && "close".equals ( methodName ) ) {
            // Workaround for BISERVER-11782. By this moment invocation of closeClientOperation did it's job and failed
            // trying to unlock not locked lock, just ignore this.
            return null;
          } else {
            throw cause;
          }
        } else {
          throw t;
        }
      }
    }

    private Statement getStatement () {
      return st;
    }

    /**
     * Gets the string value from the current row at the column with the specified name.
     *
     * @param columnName the column name
     * @return the string value of the row at the column with the specified name
     * @throws SQLException if the column name cannot be found
     */
    public String getString ( String columnName ) throws SQLException {

      String columnVal = null;
      Throwable exception = null;
      try {
        columnVal = rs.getString ( columnName );
      } catch ( Throwable t ) {
        // Save for returning later
        exception = t;
      }
      if ( columnVal != null ) {
        return columnVal;
      }
      // getString() didn't work, let's try searching the columns for the name
      ResultSetMetaData rsmd = rs.getMetaData ();
      int colCount = rsmd.getColumnCount ();
      int i = 1;
      while ( i <= colCount && !rsmd.getColumnName ( i ).equals ( columnName ) ) {
        i++;
      }
      if ( i <= colCount ) {
        columnVal = rs.getString ( i );
      }

      return columnVal;
    }
  }

  /**
   * ResultSetMetaDataInvocationHandler is a proxy handler class for java.sql.ResultSetMetaData. However the code in
   * this file is specifically for handling Hive JDBC calls, and therefore should not be used to proxy any other JDBC
   * objects besides those provided by Hive.
   */
  private static class ResultSetMetaDataInvocationHandler implements InvocationHandler {

    /**
     * The "real" ResultSetMetaData object.
     */
    ResultSetMetaData rsmd;

    /**
     * Instantiates a new result set meta data invocation handler.
     *
     * @param r the r
     */
    public ResultSetMetaDataInvocationHandler ( ResultSetMetaData r ) {
      rsmd = r;
    }

    /**
     * Intercepts methods called on the ResultSetMetaData object to possibly perform alternate processing.
     *
     * @param proxy  the proxy
     * @param method the method
     * @param args   the args
     * @return the object
     * @throws Throwable the throwable
     */
    @Override
    public Object invoke ( Object proxy, Method method, Object[] args ) throws Throwable {
      try {
        String methodName = method.getName ();
        if ( ("getColumnName".equals ( methodName ) || ("getColumnLabel".equals ( methodName ))) && (args != null) && (args.length == 1) ) {
          return getColumnName ( (Integer) args[0] );
        }
        return method.invoke ( this.rsmd, args );
      } catch ( Throwable t ) {
        if ( (t instanceof InvocationTargetException) ) {
          Throwable cause = t.getCause ();
          if ( (cause instanceof SQLException) ) {
            if ( cause.getMessage ().equals ( "Method not supported" ) ) {
              String methodName = method.getName ();
              if ( "isSigned".equals ( methodName ) ) {
                if ( args != null ) {
                  return isSigned ( (Integer) args[0] );
                }
              }
              throw cause;
            }
            throw cause;
          }
          throw cause;
        }
        throw t;
      }
    }

    private String getColumnName ( Integer column ) throws SQLException {
      String columnName = null;
      columnName = this.rsmd.getColumnName ( column );
      if ( columnName != null ) {
        int dotIndex = columnName.indexOf ( '.' );
        if ( dotIndex != -1 ) {
          return columnName.substring ( dotIndex + 1 );
        }
      }
      return columnName;
    }

    /**
     * Returns a true if values in the column are signed, false if not.
     * <p/>
     * This method checks the type of the passed column.  If that type is not numerical, then the result is false. If
     * the type is a numeric then a true is returned.
     *
     * @param column the index of the column to test
     * @return boolean
     * @throws SQLException the sQL exception
     */
    public boolean isSigned ( int column ) throws SQLException {
      int numCols = rsmd.getColumnCount ();

      if ( column < 1 || column > numCols ) {
        throw new SQLException ( "Invalid column value: " + column );
      }

      // we need to convert the thrift type to the SQL type
      int type = rsmd.getColumnType ( column );
      switch ( type ) {
        case Types.DOUBLE:
        case Types.DECIMAL:
        case Types.FLOAT:
        case Types.INTEGER:
        case Types.REAL:
        case Types.SMALLINT:
        case Types.TINYINT:
        case Types.BIGINT:
          return true;
      }
      return false;
    }
  }

  protected static boolean isInitialized () {
    return initialized;
  }

  public static void setInitialized ( boolean initialized ) {
    DriverProxyInvocationChain.initialized = initialized;
  }
}
