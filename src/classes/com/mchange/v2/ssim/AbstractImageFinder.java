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

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.imageio.*;
import com.mchange.v2.async.*;
import com.mchange.v1.io.InputStreamUtils;
import com.mchange.v1.util.ClosableResourceUtils;

abstract class AbstractImageFinder implements ImageFinder
{
    final static int BUFFER_SZ = (32 * 1024);

    SsimPersistentStore store;

    Set pendingStores = Collections.synchronizedSet( new HashSet() );

    AsynchronousRunner runner = new RoundRobinAsynchronousRunner( 3, true );

    AbstractImageFinder( SsimPersistentStore store )
    { this.store = store; }

    public void close() throws SsimException
    {
	Exception e1 = ClosableResourceUtils.attemptClose( runner );
	Exception e2 = ClosableResourceUtils.attemptClose( store );
	if ( e1 != null)
	    throw new SsimException( e1 );
	else if ( e2 != null )
	    throw new SsimException( e2 );
    }

    public ImageData find( String uid, String mimeType, int width, int height, boolean preserve_aspect_ratio  )
	throws SsimException
    {
	try
	    {
		ImageDataKey  key = ImageDataKey.findKey( uid, mimeType, width, height );
		ImageDataKey  completeKey;
		ImageData     out;

		BufferedImage originalImage = null;
		ImageSpec     origSpec      = null;
		
		ImageData     raw           = rawImageDataForUid( key.getUid() ); //does not find width & height

		// a short path...
		if ( key.getWidth() < 0 && key.getHeight() < 0 && !cacheUnmodified( key.getUid()) )
		    {
			String keyMimeType = key.getMimeType(); 
			//System.err.println( key );
			//System.err.println( keyMimeType + "   " + raw.getMimeType() );
			if (keyMimeType == null || keyMimeType.equals( raw.getMimeType() )) // we can return the raw image data...
			    return raw;                                                     // size undefined and mimeType matches
		    };

		if ( preserve_aspect_ratio || ! key.isComplete() )
		    {
			origSpec = store.originalImageSpec( key );
			if ( origSpec == null )
			    {
				InputStream is = null;
				try
				    {
					is = raw.getInputStream();
					originalImage = ImageIO.read( is );
					origSpec = new ConcreteImageSpec( raw.getMimeType(), 
									  raw.getTimestamp(),
									  originalImage.getWidth( null ),
									  originalImage.getHeight( null ) );
				    }
				finally
				    { InputStreamUtils.attemptClose( is ); }
			    }

			completeKey = SsimUtils.findCompleteKey( key, origSpec, preserve_aspect_ratio );
		    }
		else
		    completeKey = key;
		
		synchronized ( completeKey )
		    {
			while ( pendingStores.contains( completeKey ) )
			    completeKey.wait();

			//System.err.println("completeKey: " + completeKey);
			out = store.retrieve( completeKey );
			//System.err.println("out file: " + (out == null ? "null" : "" + out.getTimestamp()) +
			//		   " raw file: " + (raw == null ? "null" : "" + raw.getTimestamp()));
			if ( out != null && ( out.getTimestamp() > raw.getTimestamp() ) )
			    {
				//System.err.println("returning prescaled image: " + completeKey);
				return out;
			    }
			else //we have to recreate and store this...
			    {
				//System.err.println("(re)creating and storing image: " + completeKey);
				String outputMimeType = completeKey.getMimeType();
				byte[] bytes;
				
				if ( originalImage != null )
				    bytes = SsimUtils.bufferedImageToScaledBytes( originalImage, 
										  outputMimeType,
										  completeKey.getWidth(),
										  completeKey.getHeight() );
				else
				    {
					InputStream is = null;
					try
					    {
						is = raw.getInputStream();
						bytes = SsimUtils.streamToScaledBytes( is, 
										       outputMimeType,
										       completeKey.getWidth(),
										       completeKey.getHeight() );
					    }
					finally
					    { InputStreamUtils.attemptClose( is ); }
				    }
				Runnable imageStoreTask = new ImageStoreTask( completeKey, bytes, origSpec );
				pendingStores.add( completeKey );
				runner.postRunnable( imageStoreTask );
				return new BufferedImageData( outputMimeType, 
							      bytes,
							      System.currentTimeMillis(), 
							      completeKey.getWidth(), 
							      completeKey.getHeight() );
			    }
		    }
	    }
	catch ( SsimException e )
	    {
		e.printStackTrace();
		throw e;
	    }
	catch ( Exception e )
	    {
		e.printStackTrace();
		throw new SsimException( e );
	    }
    }

    class ImageStoreTask implements Runnable
    {
	ImageDataKey completeKey;
	byte[]       bytes;
	ImageSpec    originalImageSpec;

	ImageStoreTask( ImageDataKey completeKey, byte[] bytes, ImageSpec originalImageSpec )
	{
	    this.completeKey       = completeKey;
	    this.bytes             = bytes;
	    this.originalImageSpec = originalImageSpec;
	}

	public void run()
	{
	    synchronized( completeKey )
		{
		    try
			{ store.store( completeKey, bytes, originalImageSpec ); }
		    catch ( SsimException e )
			{
			    // if we fail, the store fails. Oh well, 
			    // better luck next time.
			    e.printStackTrace(); 
			}
		    finally
			{ 
			    pendingStores.remove( completeKey );
			    completeKey.notifyAll();
			}
		}
	}
    }


    /**
     * May return a null mime type, or -1 for the timestamp, width, or height, if those cannot be determined!
     * This should be returned FAST. The image data SHOULD NOT BE READ AND BUFFERED, at least not
     * prior to a call to getInputStream()!
     *
     * By default calls urlForUid() and generates a suitable ImageData from the URL object.
     */
    ImageData rawImageDataForUid( final String uid ) throws Exception
    {
	URL u = urlForUid( uid );
	//System.err.println("URL: " + uid);
	final URLConnection uc = u.openConnection();
	return new ImageData()
	    {
		public String getMimeType()
		{ 
		    String out = uc.getContentType(); 
		    if (out == null)
			out = SsimUtils.mimeTypeFromUid( uid );
		    return out;
		}

		public InputStream getInputStream() throws IOException
		{ return new BufferedInputStream( uc.getInputStream(), BUFFER_SZ ); }
		
		public long getTimestamp()
		{ return uc.getLastModified(); }
		
		public int getContentLength()
		{ return uc.getContentLength(); }

		public int getWidth()
		{ return -1; }

		public int getHeight()
		{ return -1; }
	    };
    }

    protected abstract URL urlForUid( String uid ) throws Exception;

    protected abstract boolean cacheUnmodified( String uid ) throws Exception;
}
