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
import javax.imageio.*;
import java.util.*;
import java.util.List;

public final class SsimUtils
{
    final static int GUESSED_COMPRESSION = 3;

    final static String PREFERRED_DEFAULT_MIME_TYPE = "image/jpeg";

    final static List readableMimeTypes;
    final static List writableMimeTypes;

    final static Map suffixesToMimeTypes; //suffixes are all lower case

    static
    {
	String[] rtypes = ImageIO.getReaderMIMETypes();
	if ( rtypes.length == 0 )
	    readableMimeTypes = null;
	else
	    readableMimeTypes = Collections.unmodifiableList( Arrays.asList( rtypes ) );

	String[] wtypes = ImageIO.getWriterMIMETypes();
	if ( wtypes.length == 0 )
	    writableMimeTypes = null;
	else
	    writableMimeTypes = Collections.unmodifiableList( Arrays.asList( wtypes ) );

	Map tmpStmt = new HashMap();
	tmpStmt.put( "gif",   "image/gif" );
	tmpStmt.put( "jpg",   "image/jpeg" );
	tmpStmt.put( "jpeg",  "image/jpeg" );
	tmpStmt.put( "png",   "image/png" );
	tmpStmt.put( "tif",   "image/tiff" );
	tmpStmt.put( "tiff",  "image/tiff" );

	Map extrasMap = new HashMap();
	for (Iterator ii = tmpStmt.keySet().iterator(); ii.hasNext(); )
	    {
		String lcKey = (String) ii.next();
		Object val   = tmpStmt.get( lcKey );

		String slcKey = 's' + lcKey;
		

		extrasMap.put( slcKey, val );
		extrasMap.put( lcKey.toUpperCase(), val );
		extrasMap.put( slcKey.toUpperCase(), val );
	    }
	tmpStmt.putAll( extrasMap );
	suffixesToMimeTypes = Collections.unmodifiableMap( tmpStmt );
    }

    public static boolean readableSuffix( String imageUid )
    {
	int last_dot = imageUid.lastIndexOf('.');
	if (last_dot < 0)
	    return false;
	else
	    {
		String suffix = imageUid.substring( last_dot + 1 );
		Object suffixMimeType = suffixesToMimeTypes.get( suffix );
		return ( suffixMimeType == null ? false : readableMimeTypes.contains( suffixMimeType ) );
	    }
    }

    static ImageDataKey findCompleteKey( ImageDataKey incompleteKey, 
					 ImageSpec origSpec,
					 boolean preserve_aspect ) throws SsimException
    {
	//System.err.println("...finding complete key from " + incompleteKey);

	int orig_w = origSpec.getWidth();
	int orig_h = origSpec.getHeight();

	String inMimeType = incompleteKey.getMimeType();
	int    in_w = incompleteKey.getWidth();
	int    in_h = incompleteKey.getHeight();

	String outMimeType = null;
	int    out_w;
	int    out_h;

	if ( inMimeType == null )
	    {
		try
		    {
			String origMimeType = origSpec.getMimeType();
			if ( writableMimeTypes.contains( origMimeType ) )
			    outMimeType = origMimeType;
			else if ( writableMimeTypes.contains( PREFERRED_DEFAULT_MIME_TYPE ) )
			    outMimeType = PREFERRED_DEFAULT_MIME_TYPE;
			else 
			    outMimeType = (String) writableMimeTypes.get(0);

			//System.err.println( "outMimeType: " + outMimeType );
		    }
		catch ( NullPointerException e )
		    {
			e.printStackTrace();
			throw new SsimException( "The ImageIO libraries do not have ImageWriters registered for ANY format! SSIM is toast." );
		    }
	    }
	else
	    outMimeType = inMimeType;

	if ( preserve_aspect && in_w >= 0 && in_h >= 0 )
	    {
		float aspect_orig     = ((float) orig_w) / orig_h;
		float aspect_bounding = ((float) in_w) / in_h;
		if ( aspect_bounding > aspect_orig )
		    in_w = -1;
		else
		    in_h = -1;
	    }

	if ( in_w < 0 && in_h < 0)
	    {
		out_w = orig_w;
		out_h = orig_h;
	    }
	else if (in_w < 0)
	    {
		float scale_factor = ((float) in_h) / origSpec.getHeight();
		out_w = Math.round( origSpec.getWidth() * scale_factor );
		out_h = in_h;
	    }
	else if (in_h < 0)
	    {
		float scale_factor = ((float) in_w) / origSpec.getWidth();
		out_w = in_w;
		out_h = Math.round( origSpec.getHeight() * scale_factor );
	    }
	else
	    {
		out_w = in_w;
		out_h = in_h;
	    }
	return ImageDataKey.findKey( incompleteKey.getUid(), outMimeType, out_w, out_h );
	
    }

    static byte[] streamToScaledBytes( InputStream is, String outputMimeType, int scaled_width, int scaled_height )
	throws SsimException, IOException
    { return bufferedImageToScaledBytes( ImageIO.read( is ), outputMimeType, scaled_width, scaled_height ); }

    static byte[] bufferedImageToScaledBytes( BufferedImage originalImage, String outputMimeType, int scaled_width, int scaled_height )
	throws SsimException, IOException
    {
	// System.err.println("Original Image Type: " + originalImage.getType());
	int type = imageType( originalImage, outputMimeType );
	BufferedImage scaledImage = new BufferedImage( scaled_width, scaled_height, type );
	Graphics2D g2d = (Graphics2D) scaledImage.getGraphics();
	g2d.drawImage( originalImage, 0, 0, scaled_width, scaled_height, null );
	String informalName = informalNameFromMimeType( outputMimeType );
	ByteArrayOutputStream bufferStream = new ByteArrayOutputStream( (scaled_width * scaled_height * 4) / GUESSED_COMPRESSION );
	boolean success = informalName != null && ImageIO.write( scaledImage, informalName, bufferStream );
	if (success)
	    return bufferStream.toByteArray();
	else
	    throw new SsimException("Unable to write to output type: [ outputMimeType: " + outputMimeType +
				    ", informalName: " + informalName + " ]");
    }

    static String informalNameFromMimeType( String mimeType )
    {
	if (mimeType.equals("image/gif"))
	    return "gif";
	else if (mimeType.equals("image/png"))
	    return "png";
	else if (mimeType.equals("image/jpeg"))
	    return "jpg";
	else if (mimeType.equals("image/tiff"))
	    return "tiff";
	else
	    return null;
    }

    // this is very hackish trial & error stuff...
    private static int imageType( BufferedImage origImage, String outputMimeType )
    {
	int origType = origImage.getType();
	if (  origType > 0  && 
	      origType != BufferedImage.TYPE_BYTE_INDEXED && 
	      origType != BufferedImage.TYPE_BYTE_GRAY)
	    return origImage.getType();
	else if ( outputMimeType.equals( "image/png" ) )
	    return BufferedImage.TYPE_INT_ARGB;
	else if ( outputMimeType.equals( "image/jpeg" ) )
	    return BufferedImage.TYPE_3BYTE_BGR;
	else //who knows?
	    return BufferedImage.TYPE_INT_ARGB;
    }

    static String mimeTypeFromUid( String uid )
    {
	String lcuid = uid.toLowerCase();
	int last_dot = lcuid.lastIndexOf('.');
	if (last_dot < 0)
	    return null;
	else
	    return lcuid.substring( last_dot + 1 );

// 	if (lcuid.endsWith(".gif") || lcuid.endsWith(".sgif"))
// 	    return "image/gif";
// 	else if (lcuid.endsWith(".jpg") || lcuid.endsWith(".jpeg") || lcuid.endsWith(".sjpg") || uid.endsWith(".sjpeg"))
// 	    return "image/jpeg";
// 	else if (lcuid.endsWith(".png") || lcuid.endsWith(".spng"))
// 	    return "image/png";
// 	else if (lcuid.endsWith(".tif") || lcuid.endsWith(".tiff") || lcuid.endsWith(".stif") || lcuid.endsWith(".stiff"))
// 	    return "image/tiff";
// 	else
// 	    return null;
    }

    static int undistortedWidth( int height, int orig_width, int orig_height )
    {
	float orig_aspect = ((float) orig_width) / orig_height;
	return Math.round( orig_aspect * height );
    }

    static int undistortedHeight( int width, int orig_width, int orig_height )
    {
	float orig_aspect = ((float) orig_width) / orig_height;
	return Math.round( width / orig_aspect );
    }

    static boolean aspectRatioMatches( int orig_width, 
					      int orig_height, 
					      int scaled_width, 
					      int scaled_height )
    {
	boolean out =  scaled_width == undistortedWidth( scaled_height, orig_width, orig_height );
	//System.err.println( "aspectRatioMatches: " + out);
	return out;
// 	float orig_aspect = ((float) orig_width) / orig_height;
// 	float scaled_aspect = ((float) scaled_width) / scaled_height;
// 	if ( Math.abs( orig_aspect - scaled_aspect ) < 0.0001f )
// 	    return true;
// 	else
// 	    return false;
    }
}


//     public static boolean isIncompleteKey( ImageDataKey key )
//     { return ( key.getMimeType() == null || key.getWidth() < 0 || key.getHeight() < 0 ); }

//     public static ScaleRec streamToScaleRec( InputStream is, String origMimeType, String outputMimeType, int width, int height )
// 	throws SsimException, IOException
//     {
// 	BufferedImage origImage = ImageIO.read( is );
// 	int orig_width = origImage.getWidth( null );
// 	int orig_height = origImage.getHeight( null );
// 	int scaled_width;
// 	int scaled_height;

// 	//deal with less-than-zero values...
// 	width = Math.max( width, -1 );
// 	height = Math.max( height, -1);
// 	if ( width < 0 && height < 0)
// 	    {
// 		scaled_width = orig_width;
// 		scaled_height = orig_height;
// 	    }
// 	else if (width < 0)
// 	    {
// 		float scale_factor = ((float) height) / orig_height;
// 		scaled_width = Math.round(orig_width * scale_factor);
// 		scaled_height = height;
// 	    }
// 	else if (height < 0)
// 	    {
// 		float scale_factor = ((float) width) / orig_width;
// 		scaled_width = width;
// 		scaled_height = Math.round(orig_height * scale_factor);
// 	    }
// 	else
// 	    {
// 		scaled_width = width;
// 		scaled_height = height;
// 	    }

// 	BufferedImage scaledImage = new BufferedImage( scaled_width, scaled_height, origImage.getType() );
// 	Graphics2D g2d = (Graphics2D) scaledImage.getGraphics();
// 	g2d.drawImage( origImage, 0, 0, scaled_width, scaled_height, null );
// 	String informalName = informalNameFromMimeType( outputMimeType );
// 	ByteArrayOutputStream bufferStream = new ByteArrayOutputStream( (scaled_width * scaled_height * 4) / GUESSED_COMPRESSION );
// 	boolean success = informalName != null && ImageIO.write( scaledImage, informalName, bufferStream );
// 	if (success)
// 	    return new ScaleRec( bufferStream.toByteArray(), origMimeType, outputMimeType, orig_width, orig_height, scaled_width, scaled_height );
// 	else
// 	    throw new SsimException("Unable to write to output type: [ outputMimeType: " + outputMimeType +
// 				    ", informalName: " + informalName + " ]");
//     }

//     final static class ScaleRec
//     {
// 	byte[] scaledBytes;

// 	String origMimeType;
// 	String outputMimeType;

// 	int    orig_width;
// 	int    orig_height;
// 	int    scaled_width;
// 	int    scaled_height;

// 	private ScaleRec( byte[] scaledBytes, String origMimeType, String outputMimeType, int orig_width, int orig_height, int scaled_width, int scaled_height )
// 	{
// 	    this.scaledBytes = scaledBytes;
// 	    this.origMimeType = origMimeType;
// 	    this.outputMimeType = outputMimeType;
// 	    this.orig_width = orig_width;
// 	    this.orig_height = orig_height;
// 	    this.scaled_width = scaled_width;
// 	    this.scaled_height = scaled_height;
// 	}
//     }

//     public static byte[] streamToScaledBytes( InputStream is, String outputMimeType, int width, int height)
// 	throws SsimException, IOException
//     {
// 	BufferedImage origImage = ImageIO.read( is );
// 	int orig_width = origImage.getWidth( null );
// 	int orig_height = origImage.getHeight( null );
// 	int scaled_width;
// 	int scaled_height;

// 	//deal with less-than-zero values...
// 	width = Math.max( width, -1 );
// 	height = Math.max( height, -1);
// 	if ( width < 0 && height < 0)
// 	    {
// 		scaled_width = orig_width;
// 		scaled_height = orig_height;
// 	    }
// 	else if (width < 0)
// 	    {
// 		float scale_factor = ((float) height) / orig_height;
// 		scaled_width = Math.round(orig_width * scale_factor);
// 		scaled_height = height;
// 	    }
// 	else if (height < 0)
// 	    {
// 		float scale_factor = ((float) width) / orig_width;
// 		scaled_width = width;
// 		scaled_height = Math.round(orig_height * scale_factor);
// 	    }
// 	else
// 	    {
// 		scaled_width = width;
// 		scaled_height = height;
// 	    }

// 	BufferedImage scaledImage = new BufferedImage( scaled_width, scaled_height, origImage.getType() );
// 	Graphics2D g2d = (Graphics2D) scaledImage.getGraphics();
// 	g2d.drawImage( origImage, 0, 0, scaled_width, scaled_height, null );
// 	String informalName = informalNameFromMimeType( outputMimeType );
// 	ByteArrayOutputStream bufferStream = new ByteArrayOutputStream( (scaled_width * scaled_height * 4) / GUESSED_COMPRESSION );
// 	boolean success = informalName != null && ImageIO.write( scaledImage, informalName, bufferStream );
// 	if (success)
// 	    return bufferStream.toByteArray();
// 	else
// 	    throw new SsimException("Unable to write to output type: [ outputMimeType: " + outputMimeType +
// 				    ", informalName: " + informalName + " ]");
//     }

