/*
    This file is part of the osmrmhv library.

    osmrmhv is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    osmrmhv is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with osmrmhv. If not, see <http://www.gnu.org/licenses/>.
*/

package de.cdauth.osm.lib;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * With this class you can easily cache OSM objects you retrieved from the API and that are not versioned. There
 * is a maximum number of cached values {@link #MAX_CACHED_VALUES} and a maximum age {@link #MAX_AGE}. Run
 * {@link #cleanUpAll} regularly to apply these limits.
 * 
 * <p>This class is to be used by the API implementations. These should check if objects are cached before fetching
 * them from the API. As this class looks up cached objects only by their ID, you need multiple instances for different
 * object types (ID scopes).
 * @author Candid Dauth
 */

public class ItemCache<T extends Item>
{
	/**
	 * How many entries may be in the cache?
	 */
	public static final int MAX_CACHED_VALUES = 0;
	/**
	 * How old may the entries in the cache be at most? (seconds)
	 */
	public static final int MAX_AGE = 600;

	/**
	 * How many entries may be in the database cache?
	 */
	public static final int MAX_DATABASE_VALUES = 5000;
	/**
	 * How old may the entries in the database cache be at most? (seconds)
	 */
	public static final int MAX_DATABASE_AGE = 86400;

	private static final Logger sm_logger = Logger.getLogger(ItemCache.class.getName());

	private final String m_persistenceID;
	private DataSource m_dataSource = null;
	private final Set<ID> m_databaseCache = Collections.synchronizedSet(new HashSet<ID>());
	
	private static final Map<ItemCache<? extends Item>, java.lang.Object> sm_instances = Collections.synchronizedMap(new WeakHashMap<ItemCache<? extends Item>, java.lang.Object>());

	private final Hashtable<ID,T> m_cache = new Hashtable<ID,T>();

	/**
	 * Caches the time stamp ({@link System#currentTimeMillis()}) when an entry is saved to the cache. Needed for all
	 * clean up methods.
	 */
	private final SortedMap<Long,ID> m_cacheTimes = Collections.synchronizedSortedMap(new TreeMap<Long,ID>());

	/**
	 * Creates a cache that does not use a database but only stores several items in the memory.
	 */
	public ItemCache()
	{
		this(null, null);
	}

	/**
	 * Creates a cache that uses a database backend to have additional capacity.
	 * @param a_dataSource The database connection to use.
	 * @param a_persistenceID An ID to refer to this cache object in the database. No two caches with the same ID may exist at
	 *                        the same time.
	 */
	public ItemCache(DataSource a_dataSource, String a_persistenceID)
	{
		m_dataSource = a_dataSource;
		m_persistenceID = a_persistenceID;

		if(getPersistenceID() != null)
			updateDatabaseCacheList();

		synchronized(sm_instances)
		{
			sm_instances.put(this, null);
		}
	}

	/**
	 * Returns the object with the ID a_id. For versioned objects returns the version that is known to be the current
	 * one.
	 * @param a_id The ID of the object.
	 * @return The object or null if it is not in the cache.
	 */
	public T getObject(ID a_id)
	{
		T ret = null;
		synchronized(m_cache)
		{
			ret = m_cache.get(a_id);
		}

		if(ret == null)
		{
			synchronized(m_databaseCache)
			{
				if(m_databaseCache.contains(a_id))
				{
					try
					{
						String persistenceID = getPersistenceID();
						Connection conn = getConnection();
						if(persistenceID != null && conn != null)
						{
							PreparedStatement stmt = conn.prepareStatement("SELECT \"data\" FROM \"osmrmhv_cache\" WHERE \"cache_id\" = ? AND \"object_id\" = ?");
							stmt.setString(1, persistenceID);
							stmt.setLong(2, a_id.asLong());
							ResultSet res = stmt.executeQuery();
							if(res.next())
								ret = (T)getSerializedObjectFromDatabase(res, 1);
							res.close();
						}
					}
					catch(Exception e)
					{
						sm_logger.log(Level.WARNING, "Could not get object from database.", e);
					}
				}
			}
		}

		return ret;
	}
	
	/**
	 * Caches an object.
	 * @param a_object The object to cache.
	 */
	public void cacheObject(T a_object)
	{
		ID id = a_object.getID();

		synchronized(m_cache)
		{
			synchronized(m_cacheTimes)
			{
				m_cache.put(id, a_object);
				m_cacheTimes.put(System.currentTimeMillis(), id);
			}
		}
	}

	/**
	 * Returns an ID to use in the database cache to identify this cache object.
	 * @return A unique ID for this cache object or null if no database cache is used.
	 */
	protected String getPersistenceID()
	{
		if(m_dataSource == null)
			return null;
		else
			return m_persistenceID;
	}

	/**
	 * Returns the connection to the cache database.
	 * @return A database connection or null if no database cache is used.
	 */
	protected Connection getConnection() throws SQLException
	{
		if(m_dataSource == null)
			return null;

		Connection ret = m_dataSource.getConnection();
		ret.setAutoCommit(false);
		return ret;
	}
	
	/**
	 * Clean up entries from the memory cache that exceed {@link #MAX_CACHED_VALUES} or {@link #MAX_AGE}. If a database
	 * cache is used, the entries are moved there.
	 */
	protected void cleanUpMemory()
	{
		String persistenceID = getPersistenceID();
		Connection conn = null;
		try
		{
			conn = getConnection();
		}
		catch(SQLException e)
		{
			sm_logger.log(Level.WARNING, "Could not move memory cache to database, could not open connection.", e);
		}

		sm_logger.info("Cache "+getPersistenceID()+" contains "+m_cache.size()+" entries.");

		int affected = 0;

		while(true)
		{
			T item;
			synchronized(m_cache)
			{
				synchronized(m_cacheTimes)
				{
					Long oldest = m_cacheTimes.firstKey();
					if(oldest == null || (System.currentTimeMillis()-oldest <= MAX_AGE*1000 && m_cacheTimes.size() <= MAX_CACHED_VALUES))
						break;
					ID id = m_cacheTimes.remove(oldest);
					item = m_cache.remove(id);
				}
			}

			affected++;

			if(persistenceID != null && conn != null)
			{
				try
				{
					synchronized(conn)
					{
						try
						{
							PreparedStatement stmt = conn.prepareStatement("DELETE FROM \"osmrmhv_cache\" WHERE \"cache_id\" = ? AND \"object_id\" = ?");
							stmt.setString(1, persistenceID);
							stmt.setLong(2, item.getID().asLong());
							stmt.execute();

							stmt = conn.prepareStatement("INSERT INTO \"osmrmhv_cache\" ( \"cache_id\", \"object_id\", \"data\", \"date\" ) VALUES ( ?, ?, ?, ? )");
							stmt.setString(1, persistenceID);
							stmt.setLong(2, item.getID().asLong());
							putSerializedObjectInDatabase(stmt, 3, item);
							stmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
							stmt.execute();

							synchronized(m_databaseCache)
							{
								m_databaseCache.add(item.getID());
							}
						}
						catch(Exception e)
						{
							conn.rollback();
							throw e;
						}
					}
				}
				catch(Exception e)
				{
					sm_logger.log(Level.WARNING, "Could not cache object in database.", e);
				}
			}
		}

		if(persistenceID != null && conn != null)
			sm_logger.info("Moved "+affected+" entries to the database cache.");
		else
			sm_logger.info("Removed "+affected+" entries from the memory.");
	}

	/**
	 * Removes all entries from the database cache that exceed {@link #MAX_DATABASE_AGE} or {@link #MAX_DATABASE_VALUES}.
	 * If no database cache is used, nothing is done.
	 */
	protected void cleanUpDatabase()
	{
		int affected = 0;
		try
		{
			String persistenceID = getPersistenceID();
			Connection conn = getConnection();

			if(persistenceID == null || conn == null)
				return;

			synchronized(conn)
			{
				PreparedStatement stmt = conn.prepareStatement("DELETE FROM \"osmrmhv_cache\" WHERE \"cache_id\" = ? AND \"date\" < ?");
				stmt.setString(1, persistenceID);
				stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()-MAX_DATABASE_AGE*1000));
				affected += stmt.executeUpdate();
				conn.commit();

				stmt = conn.prepareStatement("DELETE FROM \"osmrmhv_cache\" WHERE \"cache_id\" = ? ORDER BY \"date\" DESC OFFSET ?");
				stmt.setString(1, persistenceID);
				stmt.setInt(2, MAX_DATABASE_VALUES);
				affected += stmt.executeUpdate();
				conn.commit();
			}
		}
		catch(SQLException e)
		{
			sm_logger.log(Level.WARNING, "Could not clean up database cache.", e);
		}

		if(affected > 0)
		{
			sm_logger.info("Removed "+affected+" old cache entries from the database.");
			updateDatabaseCacheList();
		}
	}

	/**
	 * Regenerates the internal cache of all IDs that are in the database cache.
	 */
	private void updateDatabaseCacheList()
	{
		try
		{
			String persistenceID = getPersistenceID();
			if(persistenceID == null)
				return;
			Connection conn = getConnection();
			if(conn == null)
				return;
			PreparedStatement stmt = conn.prepareStatement("SELECT \"object_id\" FROM \"osmrmhv_cache\" WHERE \"cache_id\" = ?");
			stmt.setString(1, persistenceID);
			ResultSet res = stmt.executeQuery();
			synchronized(m_databaseCache)
			{
				m_databaseCache.clear();
				while(res.next())
					m_databaseCache.add(new ID(res.getLong(1)));
			}
			res.close();
		}
		catch(SQLException e)
		{
			sm_logger.log(Level.WARNING, "Could not initialise database cache.", e);

			m_dataSource = null;
		}
	}

	/**
	 * Runs {@link #cleanUpMemory()} and {@link #cleanUpDatabase()} on all instances of this class.
	 */
	public static void cleanUpAll()
	{
		ItemCache<? extends Item>[] instances;
		synchronized(sm_instances)
		{ // Copy the list of instances to avoid locking the instances list (and thus preventing the creation of new
		  // instances) during the cleanup process.
			instances = sm_instances.keySet().toArray(new ItemCache[sm_instances.size()]);
		}
		for(ItemCache<? extends Item> instance : instances)
		{
			instance.cleanUpMemory();
			instance.cleanUpDatabase();
		}
	}

	protected Serializable getSerializedObjectFromDatabase(ResultSet a_res, int a_idx) throws SQLException
	{
		try
		{
			return (Serializable)new ObjectInputStream(new ByteArrayInputStream(a_res.getBytes(a_idx))).readObject();
		}
		catch(Exception e)
		{
			if(e instanceof SQLException)
				throw (SQLException)e;
			else
				throw new SQLException("Could not unserialize object.", e);
		}
	}

	protected void putSerializedObjectInDatabase(PreparedStatement a_stmt, int a_idx, Serializable a_obj) throws SQLException
	{
		try
		{
			ByteArrayOutputStream ser = new ByteArrayOutputStream();
			ObjectOutputStream ser2 = new ObjectOutputStream(ser);
			ser2.writeObject(a_obj);
			ser2.close();
			byte[] bytes = ser.toByteArray();
			ByteArrayInputStream in = new ByteArrayInputStream(bytes);
			a_stmt.setBinaryStream(a_idx, in, bytes.length);
		}
		catch(IOException e)
		{
			throw new SQLException("Could not serialize object.", e);
		}
	}
}