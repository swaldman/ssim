/*
 * Distributed as part of ssim v.0.6.0
 *
 * Copyright (C) 2005 Machinery For Change, Inc.
 *
 * Author: Steve Waldman <swaldman@mchange.com>
 *
 * This package is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2, as 
 * published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software; see the file LICENSE.  If not, write to the
 * Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA 02111-1307, USA.
 */


package com.mchange.v2.ssim;

import java.io.*;
import java.util.*;
import java.net.URLEncoder;
import com.mchange.v1.io.InputStreamUtils;
import com.mchange.v1.io.OutputStreamUtils;
import com.mchange.v2.io.FileUtils;
import com.mchange.v2.io.DirectoryDescentUtils;
import com.mchange.v2.lock.SharedUseExclusiveUseLock;
import com.mchange.v2.lock.ExactReentrantSharedUseExclusiveUseLock;

final class DirectoryBasedPersistentStore implements SsimPersistentStore
{
    // MT: thread-safe immutable singleton
    final static Comparator ASC_LAST_MOD_COMPARATOR = new Comparator()
	{
	    public int compare( Object a, Object b )
	    {
		File aa = (File) a;
		File bb = (File) b;
		long al = aa.lastModified();
		long bl = bb.lastModified();
		if ( al < bl )
		    return -1;
		if ( al == bl )
		    return aa.compareTo( bb );
		else
		    return +1;
	    }
	};

    // MT: thread-safe immutable singleton
    final static Comparator DESC_SIZE_COMPARATOR = new Comparator()
	{
	    public int compare( Object a, Object b )
	    {
		File aa = (File) a;
		File bb = (File) b;
		long al = aa.length();
		long bl = bb.length();
		if ( al < bl )
		    return +1;
		if ( al == bl )
		    return aa.compareTo( bb );
		else
		    return -1;
	    }
	};

    // MT: thread-safe immutable singleton
    final static FileFilter INSTANCES_ONLY_FILE_FILTER = new FileFilter()
	{
	    public boolean accept( File file )
	    {
		if ( file.isDirectory() )
		    return false;
		else
		    return file.getName().startsWith("instance_");
	    }
	};

    // MT: inlined constant
    final static int BUFFER_SIZE = (32 * 1024);

    // MT: inlined constant
    final static String STORAGE_DIR_BASENAME = "SsimCacheDir_v";

    // MT: inlined constant
    final static int CACHE_FORMAT_VERSION = 1;

    // MT: synchronized MANUALLY (be careful) on locksForKeys' monitor
    Map locksForKeysAndUids =  new HashMap();

    // MT: unchanging after constructor
    final File storageDir;

    // MT: unchanging after constructor
    final long max_size_in_bytes;

    // MT: unchanging after constructor
    final int cull_delay_in_msecs;

    // MT: thread-safe concurrency control structure
    final SharedUseExclusiveUseLock globalLock = new ExactReentrantSharedUseExclusiveUseLock( this + " -- GLOBAL LOCK");

    // MT: a Thread, whose gentleStop() method is perfectly thread-safe...
    final CullThread cullThread;


    /**
     * @param max_size   the maximum size of our cache, in megabytes
     * @param cull_delay how long we should wait between attempts to cull the cache, in seconds
     */
    DirectoryBasedPersistentStore( File parentDir, long max_size, int cull_delay )
    {
	if (! parentDir.isDirectory() || ! parentDir.canWrite())
	    throw new IllegalArgumentException( parentDir.getAbsolutePath() + " must be a directory, and must be writable!");
	this.storageDir          = findCreateStorageDir( parentDir );
	this.max_size_in_bytes   = max_size * (1024 * 1024);
	this.cull_delay_in_msecs = cull_delay * 1000;
	
	if (max_size > 0 && cull_delay > 0)
	    {
		cullThread = new CullThread();
		cullThread.start();
	    }
	else
	    cullThread = null;
    }

    public ImageSpec originalImageSpec( ImageDataKey maybeIncompleteKey ) throws SsimException
    {
	try
	    {
		globalLock.acquireShared();
		try
		    { return _originalImageSpec( maybeIncompleteKey ); }
		finally
		    { globalLock.relinquishShared(); }
	    }
	catch ( Exception e )
	    {
		e.printStackTrace();
		throw new SsimException( e ); 
	    }
    }

    public void store( ImageDataKey completeKey, byte[] imageBytes, ImageSpec originalImageSpec ) throws SsimException
    {
	try
	    {
		globalLock.acquireShared();
		try
		    { _store( completeKey, imageBytes, originalImageSpec ); }
		finally
		    { globalLock.relinquishShared(); }
	    }
	catch ( Exception e )
	    {
		e.printStackTrace();
		throw new SsimException( e ); 
	    }
    }

    public ImageData retrieve( ImageDataKey completeKey ) throws SsimException
    {
	try
	    {
		globalLock.acquireShared();
		try
		    { return _retrieve( completeKey ); }
		finally
		    { globalLock.relinquishShared(); }
	    }
	catch ( Exception e )
	    {
		e.printStackTrace();
		throw new SsimException( e ); 
	    }
    }

    public void close() throws SsimException
    {
	try
	    {
		globalLock.acquireExclusive();
		try
		    { cullThread.gentleStop(); }
		finally
		    { globalLock.relinquishExclusive(); }
	    }
	catch ( Exception e )
	    {
		e.printStackTrace();
		throw new SsimException( e ); 
	    }
    }

    private ImageSpec _originalImageSpec( ImageDataKey maybeIncompleteKey ) throws Exception
    {
	String uid = maybeIncompleteKey.getUid();
	SharedUseExclusiveUseLock folderLock = findLock( uid );
	folderLock.acquireShared();
	try
	    {
		File origSpecFile = findOriginalMetadataFile( uid );
		if (! origSpecFile.exists())
		    return null;
		else
		    {
			ObjectInputStream ois = null;
			try
			    {
				ois = new ObjectInputStream( new BufferedInputStream( new FileInputStream( origSpecFile ), BUFFER_SIZE ) );
				return (ImageSpec) ois.readObject();
			    }
			finally
			    { InputStreamUtils.attemptClose( ois ); }
		    }
	    }
	finally
	    { folderLock.relinquishShared(); }
    }

    private void _store( ImageDataKey completeKey, byte[] imageBytes, ImageSpec originalImageSpec ) throws Exception
    {
	String uid = completeKey.getUid();
	ensureInitialized( uid, originalImageSpec );
	SharedUseExclusiveUseLock fileLock = findLock( completeKey );
	fileLock.acquireExclusive();
	try
	    { 
		writeInstanceFile( completeKey, imageBytes ); 
	    }
	finally
	    { fileLock.relinquishExclusive(); }
    }

    private void ensureInitialized( String uid, ImageSpec originalImageSpec ) throws Exception
    {
	SharedUseExclusiveUseLock folderLock = findLock( uid );
	folderLock.acquireShared();
	try
	    {
		File instancesDir = findInstancesDir( uid );
		if (! instancesDir.exists()) // we have to initialize
		    {
			ObjectOutputStream oos = null;
			folderLock.acquireExclusive();
			try
			    {
				instancesDir.mkdir();
				File origMetaDataFile = findOriginalMetadataFile( uid );
				oos = new ObjectOutputStream( new BufferedOutputStream( new FileOutputStream( origMetaDataFile ), BUFFER_SIZE ) );
				oos.writeObject( surelySerializable( originalImageSpec ) );
				oos.flush();
			    }
			finally
			    { 
				OutputStreamUtils.attemptClose( oos );
				folderLock.relinquishExclusive();
			    }
		    }
	    }
	finally
	    { folderLock.relinquishShared(); }
    }

    private ImageSpec surelySerializable( ImageSpec spec )
    {
	if (spec instanceof Serializable)
	    return spec;
	else
	    return new ConcreteImageSpec( spec.getMimeType(), spec.getTimestamp(), spec.getWidth(), spec.getHeight() );
    }

    private ImageData _retrieve( final ImageDataKey completeKey ) throws Exception
    {
	final SharedUseExclusiveUseLock fileLock = findLock( completeKey );
	fileLock.acquireShared();
	try
	    {
		final File storageFile = findInstanceFile( completeKey );
		//System.err.println("Checking for storageFile: " + storageFile + " [" + (storageFile.exists() ? "exists]" : "does not exist]"));
		if (! storageFile.exists())
		    return null;
		else
		    {
			// so we can used lastModified for a LRU cull of old files
			FileUtils.touchExisting( storageFile );

			// TODO: this will break if the Thread that calls getInputStream()
			//       is not the same as the one that calls close(). Should we
			//       care? We can use a different kind of lock that doesn't
			//       pay attention to which Thread is doing the work, like
			//       SimpleSharedUseExclusiveUseLock. But probably we just
			//       don't care.
			return new AbstractImageData( completeKey.getMimeType(), 
						      storageFile.lastModified(), 
						      (int) storageFile.length(), 
						      completeKey.getWidth(), 
						      completeKey.getHeight() )
			    {
				public InputStream getInputStream() throws IOException
				{ 
				    try
					{
					    fileLock.acquireShared();
					    return new BufferedInputStream( new FileInputStream( storageFile ) )
						{
						    boolean closed = false;

						    public synchronized void close() throws IOException
						    {
							try
							    {
								try { super.close(); }
								finally 
								    { 
									fileLock.relinquishShared(); 
									closed = true;
								    }
							    }
							catch ( IllegalStateException e )
							    {
								System.err.println("Uh oh... burned by the fact that this InputStream " +
										   " must be opened and closed by the same Thread.");
								e.printStackTrace();
								e.fillInStackTrace();
								throw e;
							    }
						    }

						    // backstop finalize()...
						    // closing this stream is IMPORTANT
						    // DON'T FORGET TO DO IT YOURSELF!
						    public synchronized void finalize() throws IOException 
						    {
							if (! closed)
							    {
								System.err.println("Bad move. The thread that opened an InputStream" +
										   " handed to it by DirectoryBasedPersistentStore" +
										   " failed to close it, which means the read lock on" +
										   " a file will never be released, and if the cached file" +
										   " ever becomes stale and needs to be rewritten, all attempted" +
										   " accesses will hang.");
								this.close(); 
							    }
						    }
						};
					}
				    catch (InterruptedException e)
					{
					    e.printStackTrace();
					    throw new InterruptedIOException( e.toString() );
					}
				}
			    };
		    }
		
	    }
	finally
	    { fileLock.relinquishShared(); }
    }

    private void writeInstanceFile( ImageDataKey preciseKey, byte[] imageBytes ) throws Exception
    {
	OutputStream os = null;
	try
	    {
		File storageFile = findInstanceFile( preciseKey );
		os = new BufferedOutputStream( new FileOutputStream( storageFile ), BUFFER_SIZE );
		for (int i = 0, len = imageBytes.length; i < len; ++i)
		    os.write( imageBytes[i] );
		os.flush();
	    }
	finally
	    { OutputStreamUtils.attemptClose( os );  }
    }

    private SharedUseExclusiveUseLock findLock( Object keyOrUid )
    {
	synchronized (locksForKeysAndUids)
	    {
		SharedUseExclusiveUseLock out = (SharedUseExclusiveUseLock) locksForKeysAndUids.get( keyOrUid );
		if (out == null)
		    {
			out = new ExactReentrantSharedUseExclusiveUseLock( keyOrUid.toString() );
			locksForKeysAndUids.put( keyOrUid, out );
		    }
		return out;
	    }
    }

    private File findInstancesDir( String uid )
    { return new File( storageDir, subdirName( uid ) ); }

    private File findCreateStorageDir( File parentDir )
    {
	File sdir = new File( parentDir, STORAGE_DIR_BASENAME + CACHE_FORMAT_VERSION );
	if (! sdir.exists() )
	    sdir.mkdir();
	return sdir;
    }

    private File findInstanceFile( ImageDataKey key ) throws IOException
    {
	File instancesDir = findInstancesDir( key.getUid() );
	return new File( instancesDir, instanceFileName( key ) );
    }

    private File findOriginalMetadataFile( String uid ) throws IOException
    {
 	File instancesDir = findInstancesDir( uid );
 	return new File( instancesDir, originalMetadataFileName() );
    }

    private static String subdirName( ImageDataKey key )
    { return subdirName( key.getUid() ); }

    private static String subdirName( String uid )
    {
	try
	    { return URLEncoder.encode( uid, "UTF8" ) + "_cache"; }
	catch ( UnsupportedEncodingException e ) //
	    {
		e.printStackTrace();
		throw new InternalError("UTF8 not supported???");
	    }
    }

    // obviously, if this changes, INSTANCES_ONLY_FILE_FILTER
    //  has to change as well
    private static String instanceFileName( ImageDataKey key )
    { return fileName( "instance_", key ); }

    private static String originalMetadataFileName()
    { return "original_metadata.ser"; }

    private static String fileName( String pfx, ImageDataKey key )
    {
	String mimeType = key.getMimeType();
	String dottyMimeType = ( mimeType == null ? "undefined.mime.type" : slashesToDots( mimeType ) );
	return pfx + key.getWidth() + "_x_" + key.getHeight() + '_' + dottyMimeType;
    }

    private static String slashesToDots( String s )
    {
	StringBuffer sb = new StringBuffer( s );
	for (int i = 0, len = sb.length(); i < len; ++i)
	    {
		char c = sb.charAt(i);
		if ( c == '/')
		    sb.setCharAt(i, '.');
	    }
	return sb.toString();
    }

    class CullThread extends Thread
    {
	boolean should_stop = false;

	CullThread()
	{
	    this.setName("DirectoryBasedPersistentStore.CullThread@" + Integer.toString( System.identityHashCode( this ), 16 ));
	    this.setDaemon( true );
	}

	private synchronized boolean shouldStop()
	{ return should_stop; }

	public synchronized void gentleStop()
	{ this.should_stop = true; }

	public void run()
	{
	    while (true)
		{
		    try
			{
			    Thread.sleep( cull_delay_in_msecs );
			    if ( shouldStop() )
				break;
			    cull();
			}
		    catch ( InterruptedException e )
			{ 
			    if ( shouldStop() )
				break;
			}
		    catch ( IOException e )
			{
			    // bad news, but what can we do?
			    // better luck next time, maybe.
			    e.printStackTrace();
			}
		}
	}

	private void cull() throws InterruptedException, IOException
	{
	    //System.err.println("Culling...");
	    globalLock.acquireExclusive();
	    try
		{
		    long current_size_in_bytes = FileUtils.diskSpaceUsed( storageDir );
		    //System.err.println("current_size_in_bytes: " + current_size_in_bytes + "   max_size_in_bytes: " + max_size_in_bytes);
		    if ( current_size_in_bytes > max_size_in_bytes )
			{
			    TreeSet oldestFirst = new TreeSet( ASC_LAST_MOD_COMPARATOR );
			    DirectoryDescentUtils.addSubtree( storageDir, INSTANCES_ONLY_FILE_FILTER, false, oldestFirst );
			    TreeSet biggestFirst = new TreeSet( DESC_SIZE_COMPARATOR );
			    long temp_size = current_size_in_bytes;
			    for ( Iterator ii = oldestFirst.iterator(); temp_size > max_size_in_bytes && ii.hasNext(); )
				{
				    File deadMeat = (File) ii.next();
				    long score = deadMeat.length();
				    biggestFirst.add( deadMeat );
				    temp_size -= score;
				}
			    temp_size = current_size_in_bytes;
			    for ( Iterator ii = biggestFirst.iterator(); temp_size > max_size_in_bytes && ii.hasNext(); )
				{
				    File deadMeat = (File) ii.next();
				    long score = deadMeat.length();
			    
				    //System.err.println("deleting... " + deadMeat);
				    if ( deadMeat.delete() )
					temp_size -= score;
				    
				    //very temporary test code, just to be sure I haven't fucked
				    //up. I don't want to recursively delete root by mistake....
				    //
 				    //{
 				    //   System.err.println("Want to delete " + deadMeat);
 				    //   temp_size -= score;
 				    //}
				}
			}
		}
	    finally
		{ globalLock.relinquishExclusive(); }
	    //System.err.println("Cull completed.");
	}
    }
}
