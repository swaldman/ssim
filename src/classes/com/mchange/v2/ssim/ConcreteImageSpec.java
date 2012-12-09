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
import com.mchange.v2.ser.UnsupportedVersionException;

final class ConcreteImageSpec extends AbstractImageSpec implements Serializable
{
    public ConcreteImageSpec(String mimeType, long timestamp,  int width, int height)
    { super(mimeType, timestamp,  width, height); }

    //Serialization Stuff
    static final long serialVersionUID = 1; //override to take control of versioning
    private final static short VERSION = 0x0001;
    
    private void writeObject(ObjectOutputStream out) throws IOException
    {
  	  out.writeShort(VERSION);

	  //VERSION 1
	  out.writeObject(mimeType); //can be null
	  out.writeLong(timestamp);  //can be -1
	  out.writeInt(width);       //can be -1
	  out.writeInt(height);      //can be -1
    }
    
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
	short version = in.readShort();
	switch (version)
	    {
	    case 0x0001:
		this.mimeType  = (String) in.readObject();
		this.timestamp = in.readLong();
		this.width     = in.readInt();
		this.height    = in.readInt();
		break;
	    default:
		throw new UnsupportedVersionException(this, version);
	    }
    }
}
