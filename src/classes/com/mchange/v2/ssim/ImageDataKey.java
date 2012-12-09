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
import com.mchange.v2.coalesce.*;
import com.mchange.v2.lang.ObjectUtils;
import com.mchange.v2.ser.UnsupportedVersionException;

final class ImageDataKey implements Serializable
{
    static CoalesceChecker cc = new CoalesceChecker()
    {
	public boolean checkCoalesce( Object a, Object b )
	{
	    ImageDataKey aa = (ImageDataKey) a;
	    ImageDataKey bb = (ImageDataKey) b;

	    boolean out =
		aa.width == bb.width &&
		aa.height == bb.height &&
		aa.uid == bb.uid && //uids are intern()ed strings, so the identity test is safe
		ObjectUtils.eqOrBothNull( aa.mimeType, bb.mimeType );
	    //System.err.println("checkCoalesce: " + a + "  " + b + " --> " + out);
	    return out;
	}

	public int coalesceHash( Object a )
	{
	    ImageDataKey key = (ImageDataKey) a;
	    return
		key.uid.hashCode() ^
		ObjectUtils.hashOrZero( key.mimeType ) ^
		key.width ^
		key.height;
	}
    };

    static Coalescer coalescer = CoalescerFactory.createCoalescer( cc, true, true );

    transient String uid;
    transient String mimeType;
    transient int width;
    transient int height;

    public static ImageDataKey findKey( String uid, String mimeType, int width, int height )
    {
	width = Math.max( width, -1 );
	height = Math.max( height, -1 );
	return (ImageDataKey) coalescer.coalesce( new ImageDataKey( uid, mimeType, width, height ) ); 
    }

    /**
     * Note : uids are guaranteed to be interned strings!
     */
    public String getUid()
    { return uid; }
	
    public String getMimeType()
    { return mimeType; }

    public int getWidth()
    { return width; }
	
    public int getHeight()
    { return height; }

    public boolean isComplete()
    { return (mimeType != null && width >= 0 && height >= 0); }

    public String toString()
    { return super.toString() + "[uid=" + uid + ", mimeType=" + mimeType + ", width=" + width + ", height=" + height + ']'; }

    private ImageDataKey( String uid, String mimeType, int width, int height )
    {
	this.uid = uid.intern();;
	this.mimeType = mimeType;
	this.width = width;
	this.height = height;
    }

    /*
     * We needn't override equals() or hashCode(), because outside of this
     * class there will be only one instance per value. Ensuring that appropriate
     * objects "coalesce" to enforce the one instance per value rule is handled by
     * the CoalesceChecker above.
     */

    //Serialization Stuff
    static final long serialVersionUID = 1; //override to take control of versioning
    private final static short VERSION = 0x0001;
    
    private void writeObject(ObjectOutputStream out) throws IOException
    {
  	  out.writeShort(VERSION);

	  //VERSION 1
  	  out.writeUTF(uid);
	  out.writeObject(mimeType); //can be null
	  out.writeInt(width); //can be null
	  out.writeInt(height); //can be null
    }
    
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
	short version = in.readShort();
	switch (version)
	    {
	    case 0x0001:
		this.uid      = in.readUTF().intern();
		this.mimeType = (String) in.readObject();
		this.width    = in.readInt();
		this.height   = in.readInt();
		break;
	    default:
		throw new UnsupportedVersionException(this, version);
	    }
    }

    private Object readResolve() throws ObjectStreamException
    {
	//System.err.println("XXX readResolve() called.");
	return findKey( this.uid, this.mimeType, this.width, this.height ); 
    }
}
