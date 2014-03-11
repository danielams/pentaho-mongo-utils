package org.pentaho.mongo.wrapper;

import com.mongodb.BasicDBList;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.ServerAddress;
import com.mongodb.TaggableReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.util.JSON;
import org.pentaho.mongo.BaseMessages;
import org.pentaho.mongo.MongoDbException;
import org.pentaho.mongo.MongoProp;
import org.pentaho.mongo.MongoProperties;
import org.pentaho.mongo.MongoUtilLogger;
import org.pentaho.mongo.NamedReadPreference;
import org.pentaho.mongo.Util;
import org.pentaho.mongo.wrapper.collection.DefaultMongoCollectionWrapper;
import org.pentaho.mongo.wrapper.collection.MongoCollectionWrapper;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NoAuthMongoClientWrapper implements MongoClientWrapper {
  private static Class<?> PKG = NoAuthMongoClientWrapper.class;
  public static final int MONGO_DEFAULT_PORT = 27017;

  public static final String LOCAL_DB = "local"; //$NON-NLS-1$
  public static final String REPL_SET_COLLECTION = "system.replset"; //$NON-NLS-1$
  public static final String REPL_SET_SETTINGS = "settings"; //$NON-NLS-1$
  public static final String REPL_SET_LAST_ERROR_MODES = "getLastErrorModes"; //$NON-NLS-1$
  public static final String REPL_SET_MEMBERS = "members"; //$NON-NLS-1$

  private final MongoClient mongo;
  private final MongoUtilLogger log;

  /**
   * Create a connection to a Mongo server based on parameters supplied in the step meta data
   *
   * @param props properties to use
   * @param log   for logging
   * @return a configured MongoClient object
   * @throws MongoDbException if a problem occurs
   */
  public NoAuthMongoClientWrapper( MongoProperties props, MongoUtilLogger log )
      throws MongoDbException {
    this.log = log;
    mongo = initConnection( props, log );
  }

  public NoAuthMongoClientWrapper( MongoClient mongo, MongoUtilLogger log ) {
    this.mongo = mongo;
    this.log = log;
  }

  public MongoClient getMongo() {
    return mongo;
  }

  private MongoClient initConnection( MongoProperties props, MongoUtilLogger log )
      throws MongoDbException {
    String hostsPorts = props.get( MongoProp.HOST );
    String singlePort = props.get( MongoProp.PORT );
    String connTimeout = props.get( MongoProp.CONNECT_TIMEOUT );
    String socketTimeout = props.get( MongoProp.SOCKET_TIMEOUT );
    String readPreference = props.get( MongoProp.READ_PREFERENCE );
    String writeConcern = props.get( MongoProp.WRITE_CONCERN );
    String wTimeout = props.get( MongoProp.WRITE_TIMEOUT );
    boolean journaled = Boolean.valueOf( props.get( MongoProp.JOURNALED ) );
    List<String> tagSet = null; //meta.getReadPrefTagSets();  //TODO:  parse out tag sets
    boolean useAllReplicaSetMembers = Boolean.valueOf( props.get( MongoProp.USE_ALL_REPLICA_SET_MEMBERS ) );
    int singlePortI = -1;

    try {
      singlePortI = Integer.parseInt( singlePort );
    } catch ( NumberFormatException n ) {
      // don't complain
    }

    if ( Util.isEmpty( hostsPorts ) ) {
      throw new MongoDbException(
        BaseMessages.getString( PKG, "MongoNoAuthWrapper.Message.Error.EmptyHostsString" ) ); //$NON-NLS-1$
    }

    List<ServerAddress> repSet = new ArrayList<ServerAddress>();

    String[] parts = hostsPorts.trim().split( "," ); //$NON-NLS-1$
    for ( String part : parts ) {
      // host:port?
      int port = singlePortI != -1 ? singlePortI : MONGO_DEFAULT_PORT;
      String[] hp = part.split( ":" ); //$NON-NLS-1$
      if ( hp.length > 2 ) {
        throw new MongoDbException(
          BaseMessages.getString( PKG, "MongoNoAuthWrapper.Message.Error.MalformedHost", part ) ); //$NON-NLS-1$
      }

      String host = hp[ 0 ];
      if ( hp.length == 2 ) {
        // non-default port
        try {
          port = Integer.parseInt( hp[ 1 ].trim() );
        } catch ( NumberFormatException n ) {
          throw new MongoDbException( BaseMessages.getString( PKG,
            "MongoNoAuthWrapper.Message.Error.UnableToParsePortNumber", hp[ 1 ] ) ); //$NON-NLS-1$
        }
      }

      try {
        ServerAddress s = new ServerAddress( host, port );
        repSet.add( s );
      } catch ( UnknownHostException u ) {
        throw new MongoDbException( u );
      }
    }

    MongoClientOptions.Builder mongoOptsBuilder = new MongoClientOptions.Builder();

    configureConnectionOptions( mongoOptsBuilder, connTimeout, socketTimeout, readPreference, writeConcern, wTimeout,
      journaled, tagSet, props, log );

    MongoClientOptions opts = mongoOptsBuilder.build();
    return getClient( props, log, repSet, useAllReplicaSetMembers, opts );
  }

  protected MongoClient getClient( MongoProperties props, MongoUtilLogger log,
                                   List<ServerAddress> repSet, boolean useAllReplicaSetMembers,
                                   MongoClientOptions opts ) throws MongoDbException {
    try {
      // Mongo's java driver will discover all replica set or shard
      // members (Mongos) automatically when MongoClient is constructed
      // using a list of ServerAddresses. The javadocs state that MongoClient
      // should be constructed using a SingleServer address instance (rather
      // than a list) when connecting to a stand-alone host - this is why
      // we differentiate here between a list containing one ServerAddress
      // and a single ServerAddress instance via the useAllReplicaSetMembers
      // flag.
      return ( repSet.size() > 1 || ( useAllReplicaSetMembers && repSet.size() >= 1 ) ? new MongoClient( repSet, opts )
        : ( repSet.size() == 1 ? new MongoClient( repSet.get( 0 ), opts ) : new MongoClient( new ServerAddress(
          "localhost" ), opts ) ) ); //$NON-NLS-1$
    } catch ( UnknownHostException u ) {
      throw new MongoDbException( u );
    }
  }

  /**
   * Utility method to configure Mongo connection options
   *
   * @param optsBuilder    an options builder
   * @param connTimeout    the connection timeout to use (can be null)
   * @param socketTimeout  the socket timeout to use (can be null)
   * @param readPreference the read preference to use (can be null)
   * @param writeConcern   the writeConcern to use (can be null)
   * @param wTimeout       the w timeout to use (can be null)
   * @param journaled      whether to use journaled writes
   * @param tagSet         the tag set to use in conjunction with the read preference (can be null)
   * @param props          properties to use
   * @param log            for logging
   * @throws MongoDbException if a problem occurs
   */
  private void configureConnectionOptions( MongoClientOptions.Builder optsBuilder, String connTimeout,
                                           String socketTimeout, String readPreference, String writeConcern,
                                           String wTimeout, boolean journaled,
                                           List<String> tagSet, MongoProperties props, MongoUtilLogger log )
      throws MongoDbException {

    // connection timeout
    if ( !Util.isEmpty( connTimeout ) ) {
      String connS = props.get( MongoProp.CONNECT_TIMEOUT );
      try {
        int cTimeout = Integer.parseInt( connS );
        if ( cTimeout > 0 ) {
          optsBuilder.connectTimeout( cTimeout );
        }
      } catch ( NumberFormatException n ) {
        throw new MongoDbException( n );
      }
    }

    // socket timeout
    if ( !Util.isEmpty( socketTimeout ) ) {
      String sockS = props.get( MongoProp.SOCKET_TIMEOUT );
      try {
        int sockTimeout = Integer.parseInt( sockS );
        if ( sockTimeout > 0 ) {
          optsBuilder.socketTimeout( sockTimeout );
        }
      } catch ( NumberFormatException n ) {
        throw new MongoDbException( n );
      }
    }

    if ( log != null ) {
      String rpLogSetting = NamedReadPreference.PRIMARY.getName();

      if ( !Util.isEmpty( readPreference ) ) {
        rpLogSetting = readPreference;
      }
      log.info(
        BaseMessages.getString( PKG, "MongoNoAuthWrapper.Message.UsingReadPreference", rpLogSetting ) ); //$NON-NLS-1$
    }
    DBObject firstTagSet = null;
    DBObject[] remainingTagSets = new DBObject[ 0 ];
    if ( tagSet != null && tagSet.size() > 0 ) {
      if ( tagSet.size() > 1 ) {
        remainingTagSets = new DBObject[ tagSet.size() - 1 ];
      }

      firstTagSet = (DBObject) JSON.parse( tagSet.get( 0 ).trim() );
      for ( int i = 1; i < tagSet.size(); i++ ) {
        remainingTagSets[ i - 1 ] = (DBObject) JSON.parse( tagSet.get( i ).trim() );
      }
      if ( log != null
        && ( !Util.isEmpty( readPreference )
        && !readPreference.equalsIgnoreCase( NamedReadPreference.PRIMARY.getName() ) ) ) {
        StringBuilder builder = new StringBuilder();
        for ( String s : tagSet ) {
          builder.append( s ).append( " " ); //$NON-NLS-1$
        }
        log.info( BaseMessages.getString( PKG,
          "MongoNoAuthWrapper.Message.UsingReadPreferenceTagSets", builder.toString() ) ); //$NON-NLS-1$
      }
    } else {
      if ( log != null ) {
        log.info(
          BaseMessages.getString( PKG, "MongoNoAuthWrapper.Message.NoReadPreferenceTagSetsDefined" ) ); //$NON-NLS-1$
      }
    }

    // read preference
    if ( !Util.isEmpty( readPreference ) ) {

      // TODO:  handle read preferences
      String rp = null; //vars.environmentSubstitute( readPreference );

      NamedReadPreference preference = NamedReadPreference.byName( rp );

      if ( ( firstTagSet != null ) && ( preference.getPreference() instanceof TaggableReadPreference ) ) {
        optsBuilder.readPreference( preference.getTaggableReadPreference( firstTagSet, remainingTagSets ) );
      } else {
        optsBuilder.readPreference( preference.getPreference() );
      }

    }

    // write concern
    writeConcern = props.get( MongoProp.WRITE_CONCERN );
    wTimeout = props.get( MongoProp.WRITE_TIMEOUT );

    WriteConcern concern = null;

    if ( !Util.isEmpty( writeConcern ) && Util.isEmpty( wTimeout ) && !journaled ) {
      // all defaults - timeout 0, journal = false, w = 1
      concern = new WriteConcern();
      concern.setWObject( new Integer( 1 ) );

      if ( log != null ) {
        log.info( BaseMessages
          .getString( PKG, "MongoNoAuthWrapper.Message.ConfiguringWithDefaultWriteConcern" ) ); //$NON-NLS-1$
      }
    } else {
      int wt = 0;
      if ( !Util.isEmpty( wTimeout ) ) {
        try {
          wt = Integer.parseInt( wTimeout );
        } catch ( NumberFormatException n ) {
          throw new MongoDbException( n );
        }
      }

      if ( !Util.isEmpty( writeConcern ) ) {
        // try parsing as a number first
        try {
          int wc = Integer.parseInt( writeConcern );
          concern = new WriteConcern( wc, wt, false, journaled );
        } catch ( NumberFormatException n ) {
          // assume its a valid string - e.g. "majority" or a custom
          // getLastError label associated with a tag set
          concern = new WriteConcern( writeConcern, wt, false, journaled );
        }
      } else {
        concern = new WriteConcern( 1, wt, false, journaled );
      }

      if ( log != null ) {
        String lwc =
          "w = " + concern.getW() + ", wTimeout = " + concern.getWtimeout() + ", journaled = " + concern.getJ();
        log.info( BaseMessages.getString( PKG, "MongoNoAuthWrapper.Message.ConfiguringWithWriteConcern", lwc ) );
      }
    }
    optsBuilder.writeConcern( concern );
  }

  /**
   * Retrieve all database names found in MongoDB as visible by the authenticated user.
   *
   * @throws MongoDbException
   */
  public List<String> getDatabaseNames() throws MongoDbException {
    try {
      return getMongo().getDatabaseNames();
    } catch ( Exception e ) {
      if ( e instanceof MongoDbException ) {
        throw (MongoDbException) e;
      } else {
        throw new MongoDbException( e );
      }
    }
  }

  protected DB getDb( String dbName ) throws MongoDbException {
    try {
      return getMongo().getDB( dbName );
    } catch ( Exception e ) {
      if ( e instanceof MongoDbException ) {
        throw (MongoDbException) e;
      } else {
        throw new MongoDbException( e );
      }
    }
  }

  /**
   * Get the set of collections for a MongoDB database.
   *
   * @param dB Name of database
   * @return Set of collections in the database requested.
   * @throws MongoDbException If an error occurs.
   */
  public Set<String> getCollectionsNames( String dB ) throws MongoDbException {
    try {
      return getDb( dB ).getCollectionNames();
    } catch ( Exception e ) {
      if ( e instanceof MongoDbException ) {
        throw (MongoDbException) e;
      } else {
        throw new MongoDbException( e );
      }
    }
  }

  /**
   * Return a list of custom "lastErrorModes" (if any) defined in the replica set configuration object on the server.
   * These can be used as the "w" setting for the write concern in addition to the standard "w" values of <number> or
   * "majority".
   *
   * @return a list of the names of any custom "lastErrorModes"
   * @throws MongoDbException if a problem occurs
   */
  public List<String> getLastErrorModes() throws MongoDbException {
    List<String> customLastErrorModes = new ArrayList<String>();

    DB local = getDb( LOCAL_DB );
    if ( local != null ) {
      try {
        DBCollection replset = local.getCollection( REPL_SET_COLLECTION );
        if ( replset != null ) {
          DBObject config = replset.findOne();

          extractLastErrorModes( config, customLastErrorModes );
        }
      } catch ( Exception e ) {
        if ( e instanceof MongoDbException ) {
          throw (MongoDbException) e;
        } else {
          throw new MongoDbException( e );
        }
      }
    }

    return customLastErrorModes;
  }

  protected void extractLastErrorModes( DBObject config, List<String> customLastErrorModes ) {
    if ( config != null ) {
      Object settings = config.get( REPL_SET_SETTINGS );

      if ( settings != null ) {
        Object getLastErrModes = ( (DBObject) settings ).get( REPL_SET_LAST_ERROR_MODES );

        if ( getLastErrModes != null ) {
          for ( String m : ( (DBObject) getLastErrModes ).keySet() ) {
            customLastErrorModes.add( m );
          }
        }
      }
    }
  }

  public List<String> getIndexInfo( String dbName, String collection ) throws MongoDbException {
    try {
      DB db = getDb( dbName );

      if ( db == null ) {
        throw new Exception(
          BaseMessages.getString( PKG, "MongoNoAuthWrapper.ErrorMessage.NonExistentDB", dbName ) ); //$NON-NLS-1$
      }

      if ( Util.isEmpty( collection ) ) {
        throw new Exception(
          BaseMessages.getString( PKG, "MongoNoAuthWrapper.ErrorMessage.NoCollectionSpecified" ) ); //$NON-NLS-1$
      }

      if ( !db.collectionExists( collection ) ) {
        db.createCollection( collection, null );
      }

      DBCollection coll = db.getCollection( collection );
      if ( coll == null ) {
        throw new Exception( BaseMessages.getString( PKG,
          "MongoNoAuthWrapper.ErrorMessage.UnableToGetInfoForCollection", //$NON-NLS-1$
          collection ) );
      }

      List<DBObject> collInfo = coll.getIndexInfo();
      List<String> result = new ArrayList<String>();
      if ( collInfo == null || collInfo.size() == 0 ) {
        throw new Exception( BaseMessages.getString( PKG,
          "MongoNoAuthWrapper.ErrorMessage.UnableToGetInfoForCollection", //$NON-NLS-1$
          collection ) );
      }

      for ( DBObject index : collInfo ) {
        result.add( index.toString() );
      }

      return result;
    } catch ( Exception e ) {
      log.error( BaseMessages.getString( PKG, "MongoNoAuthWrapper.ErrorMessage.GeneralError.Message" ) //$NON-NLS-1$
        + ":\n\n" + e.getMessage(), e ); //$NON-NLS-1$
      if ( e instanceof MongoDbException ) {
        throw (MongoDbException) e;
      } else {
        throw new MongoDbException( e );
      }
    }
  }

  @Override
  public List<String> getAllTags() throws MongoDbException {
    return setupAllTags( getRepSetMemberRecords() );
  }

  private BasicDBList getRepSetMemberRecords() throws MongoDbException {
    BasicDBList setMembers = null;
    try {
      DB local = getDb( LOCAL_DB );
      if ( local != null ) {

        DBCollection replset = local.getCollection( REPL_SET_COLLECTION );
        if ( replset != null ) {
          DBObject config = replset.findOne();

          if ( config != null ) {
            Object members = config.get( REPL_SET_MEMBERS );

            if ( members instanceof BasicDBList ) {
              if ( ( (BasicDBList) members ).size() == 0 ) {
                // log that there are no replica set members defined
                if ( log != null ) {
                  log.info( BaseMessages.getString( PKG,
                    "MongoNoAuthWrapper.Message.Warning.NoReplicaSetMembersDefined" ) ); //$NON-NLS-1$
                }
              } else {
                setMembers = (BasicDBList) members;
              }

            } else {
              // log that there are no replica set members defined
              if ( log != null ) {
                log.info( BaseMessages.getString( PKG,
                  "MongoNoAuthWrapper.Message.Warning.NoReplicaSetMembersDefined" ) ); //$NON-NLS-1$
              }
            }
          } else {
            // log that there are no replica set members defined
            if ( log != null ) {
              log.info( BaseMessages.getString( PKG,
                "MongoNoAuthWrapper.Message.Warning.NoReplicaSetMembersDefined" ) ); //$NON-NLS-1$
            }
          }
        } else {
          // log that the replica set collection is not available
          if ( log != null ) {
            log.info( BaseMessages.getString( PKG,
              "MongoNoAuthWrapper.Message.Warning.ReplicaSetCollectionUnavailable" ) ); //$NON-NLS-1$
          }
        }
      } else {
        // log that the local database is not available!!
        if ( log != null ) {
          log.info(
            BaseMessages.getString( PKG, "MongoNoAuthWrapper.Message.Warning.LocalDBNotAvailable" ) ); //$NON-NLS-1$
        }
      }
    } catch ( Exception ex ) {
      throw new MongoDbException( ex );
    } finally {
      if ( getMongo() != null ) {
        getMongo().close();
      }
    }

    return setMembers;
  }

  protected List<String> setupAllTags( BasicDBList members ) {
    HashSet<String> tempTags = new HashSet<String>();

    if ( members != null && members.size() > 0 ) {
      for ( int i = 0; i < members.size(); i++ ) {
        Object m = members.get( i );

        if ( m != null ) {
          DBObject tags = (DBObject) ( (DBObject) m ).get( "tags" ); //$NON-NLS-1$
          if ( tags == null ) {
            continue;
          }

          for ( String tagName : tags.keySet() ) {
            String tagVal = tags.get( tagName ).toString();
            String combined = quote( tagName ) + " : " + quote( tagVal ); //$NON-NLS-1$
            tempTags.add( combined );
          }
        }
      }
    }

    return new ArrayList<String>( tempTags );
  }

  protected static String quote( String string ) {
    if ( string.indexOf( '"' ) >= 0 ) {

      if ( string.indexOf( '"' ) >= 0 ) {
        string = string.replace( "\"", "\\\"" ); //$NON-NLS-1$ //$NON-NLS-2$
      }
    }

    string = ( "\"" + string + "\"" ); //$NON-NLS-1$ //$NON-NLS-2$

    return string;
  }

  /**
   * Return a list of replica set members whos tags satisfy the supplied list of tag set. It is assumed that members
   * satisfy according to an OR relationship = i.e. a member satisfies if it satisfies at least one of the tag sets in
   * the supplied list.
   *
   * @param tagSets the list of tag sets to match against
   * @return a list of replica set members who's tags satisfy the supplied list of tag sets
   * @throws MongoDbException if a problem occurs
   */
  public List<String> getReplicaSetMembersThatSatisfyTagSets( List<DBObject> tagSets ) throws MongoDbException {
    try {
      List<String> result = new ArrayList<String>();
      for ( DBObject object : checkForReplicaSetMembersThatSatisfyTagSets( tagSets, getRepSetMemberRecords() ) ) {
        result.add( object.toString() );
      }
      return result;
    } catch ( Exception ex ) {
      if ( ex instanceof MongoDbException ) {
        throw (MongoDbException) ex;
      } else {
        throw new MongoDbException( BaseMessages.getString( PKG,
          "MongoNoAuthWrapper.ErrorMessage.UnableToGetReplicaSetMembers" ), ex ); //$NON-NLS-1$
      }
    }
  }

  protected List<DBObject> checkForReplicaSetMembersThatSatisfyTagSets( List<DBObject> tagSets, BasicDBList members ) {
    List<DBObject> satisfy = new ArrayList<DBObject>();
    if ( members != null && members.size() > 0 ) {
      for ( int i = 0; i < members.size(); i++ ) {
        Object m = members.get( i );

        if ( m != null ) {
          DBObject tags = (DBObject) ( (DBObject) m ).get( "tags" ); //$NON-NLS-1$
          if ( tags == null ) {
            continue;
          }

          for ( int j = 0; j < tagSets.size(); j++ ) {
            boolean match = true;
            DBObject toMatch = tagSets.get( j );

            for ( String tagName : toMatch.keySet() ) {
              String tagValue = toMatch.get( tagName ).toString();

              // does replica set member m's tags contain this tag?
              Object matchVal = tags.get( tagName );

              if ( matchVal == null ) {
                match = false; // doesn't match this particular tag set
                // no need to check any other keys in toMatch
                break;
              }

              if ( !matchVal.toString().equals( tagValue ) ) {
                // rep set member m's tags has this tag, but it's value does not
                // match
                match = false;

                // no need to check any other keys in toMatch
                break;
              }
            }

            if ( match ) {
              // all tag/values present and match - add this member (only if its
              // not already there)
              if ( !satisfy.contains( m ) ) {
                satisfy.add( (DBObject) m );
              }
            }
          }
        }
      }
    }

    return satisfy;
  }

  @Override
  public MongoCollectionWrapper getCollection( String db, String name ) throws MongoDbException {
    return wrap( getDb( db ).getCollection( name ) );
  }

  @Override
  public MongoCollectionWrapper createCollection( String db, String name ) throws MongoDbException {
    return wrap( getDb( db ).createCollection( name, null ) );
  }

  protected MongoCollectionWrapper wrap( DBCollection collection ) {
    return new DefaultMongoCollectionWrapper( collection );
  }

  @Override
  public void dispose() {
    getMongo().close();
  }
}