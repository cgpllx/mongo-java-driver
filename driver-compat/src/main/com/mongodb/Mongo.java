/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb;

import org.mongodb.Codec;
import org.mongodb.Document;
import org.mongodb.MongoConnector;
import org.mongodb.MongoCredential;
import org.mongodb.annotations.ThreadSafe;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.codecs.PrimitiveCodecs;
import org.mongodb.command.ListDatabases;
import org.mongodb.impl.MongoConnectorsImpl;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ThreadSafe
public class Mongo {
    private static final String ADMIN_DATABASE_NAME = "admin";
    private static final String VERSION = "3.0.0-SNAPSHOT";

    private final ConcurrentMap<String, DB> dbCache = new ConcurrentHashMap<String, DB>();

    private volatile WriteConcern writeConcern;
    private volatile ReadPreference readPreference;

    private final Bytes.OptionHolder optionHolder;

    private final Codec<Document> documentCodec;
    private final MongoConnector connector;

    Mongo(final List<ServerAddress> seedList, final MongoClientOptions mongoOptions) {
        this(MongoConnectorsImpl.create(createNewSeedList(seedList), mongoOptions.toNew()), mongoOptions);
    }

    Mongo(final MongoClientURI mongoURI) throws UnknownHostException {
        this(createConnector(mongoURI.toNew()), mongoURI.getOptions());
    }

    Mongo(final ServerAddress serverAddress, final MongoClientOptions mongoOptions) {
        this(MongoConnectorsImpl.create(serverAddress.toNew(), mongoOptions.toNew()), mongoOptions);
    }

    Mongo(final MongoConnector connector, final MongoClientOptions options) {
        this.connector = connector;
        this.documentCodec = new DocumentCodec(PrimitiveCodecs.createDefault());
        this.readPreference = options.getReadPreference() != null ?
                options.getReadPreference() : ReadPreference.primary();
        this.writeConcern = options.getWriteConcern() != null ?
                options.getWriteConcern() : WriteConcern.UNACKNOWLEDGED;
        this.optionHolder = new Bytes.OptionHolder(null);
    }

    /**
     * Sets the write concern for this database. Will be used as default for writes to any collection in any database.
     * See the documentation for {@link WriteConcern} for more information.
     *
     * @param writeConcern write concern to use
     */
    public void setWriteConcern(final WriteConcern writeConcern) {
        this.writeConcern = writeConcern;
    }


    /**
     * Gets the default write concern
     *
     * @return the default write concern
     */
    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    /**
     * Sets the read preference for this database. Will be used as default for reads from any collection in any
     * database. See the documentation for {@link ReadPreference} for more information.
     *
     * @param readPreference Read Preference to use
     */
    public void setReadPreference(final ReadPreference readPreference) {
        this.readPreference = readPreference;
    }

    /**
     * Gets the default read preference
     *
     * @return the default read preference
     */
    public ReadPreference getReadPreference() {
        return readPreference;
    }

    /**
     * Gets the current driver version.
     *
     * @return the full version string, e.g. "3.0.0"
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * Gets the list of server addresses currently seen by the connector. This includes addresses auto-discovered from a
     * replica set.
     *
     * @return list of server addresses
     * @throws MongoException
     */
    public List<ServerAddress> getServerAddressList() {
        List<ServerAddress> retVal = new ArrayList<ServerAddress>();
        for (org.mongodb.ServerAddress serverAddress : getConnector().getServerAddressList()) {
            retVal.add(new ServerAddress(serverAddress));
        }
        return retVal;
    }


    /**
     * Gets a list of the names of all databases on the connected server.
     *
     * @return list of database names
     * @throws MongoException
     */
    public List<String> getDatabaseNames() {
        final org.mongodb.result.CommandResult listDatabasesResult;
        try {
            listDatabasesResult = getConnector().command(ADMIN_DATABASE_NAME, new ListDatabases(), documentCodec);
        } catch (org.mongodb.MongoException e) {
            throw new MongoException(e);
        }

        @SuppressWarnings("unchecked")
        final List<Document> databases = (List<Document>) listDatabasesResult.getResponse().get("databases");

        final List<String> databaseNames = new ArrayList<String>();
        for (final Document d : databases) {
            databaseNames.add(d.get("name", String.class));
        }
        return Collections.unmodifiableList(databaseNames);
    }

    /**
     * Gets a database object
     *
     * @param dbName the name of the database to retrieve
     * @return a DB representing the specified database
     */
    public DB getDB(final String dbName) {
        DB db = dbCache.get(dbName);
        if (db != null) {
            return db;
        }

        db = new DB(this, dbName, documentCodec);
        final DB temp = dbCache.putIfAbsent(dbName, db);
        if (temp != null) {
            return temp;
        }
        return db;
    }

    /**
     * Returns the list of databases used by the driver since this Mongo instance was created.
     * This may include DBs that exist in the client but not yet on the server.
     *
     * @return a collection of database objects
     */
    public Collection<DB> getUsedDatabases() {
        return dbCache.values();
    }


    /**
     * Drops the database if it exists.
     *
     * @param dbName name of database to drop
     * @throws MongoException
     */
    public void dropDatabase(final String dbName) {
        getDB(dbName).dropDatabase();
    }

    /**
     * Closes all resources associated with this instance, in particular any open network connections. Once called, this
     * instance and any databases obtained from it can no longer be used.
     */
    public void close() {
        getConnector().close();
    }

    //******* Missing functionality from the old driver *******//

    void requestStart() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    void requestDone() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    public void addOption(final int option) {
        optionHolder.add(option);
    }

    public int getOptions() {
        return optionHolder.get();
    }

    private static List<org.mongodb.ServerAddress> createNewSeedList(final List<ServerAddress> seedList) {
        List<org.mongodb.ServerAddress> retVal = new ArrayList<org.mongodb.ServerAddress>(seedList.size());
        for (ServerAddress cur : seedList) {
            retVal.add(cur.toNew());
        }
        return retVal;
    }

    private static MongoConnector createConnector(final org.mongodb.MongoClientURI mongoURI) throws UnknownHostException {
        final List<MongoCredential> credentialList
                = mongoURI.getCredentials() != null ? Arrays.asList(mongoURI.getCredentials()) : null;
        if (mongoURI.getHosts().size() == 1) {
            return MongoConnectorsImpl.create(new org.mongodb.ServerAddress(mongoURI.getHosts().get(0)),
                    credentialList, mongoURI.getOptions());
        } else {
            List<org.mongodb.ServerAddress> seedList = new ArrayList<org.mongodb.ServerAddress>();
            for (String cur : mongoURI.getHosts()) {
                seedList.add(new org.mongodb.ServerAddress(cur));
            }
            return MongoConnectorsImpl.create(seedList, credentialList, mongoURI.getOptions());
        }
    }

    protected MongoConnector getConnector() {
        return connector;
    }

    protected Bytes.OptionHolder getOptionHolder() {
        return optionHolder;
    }

}