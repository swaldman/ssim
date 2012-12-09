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

final class BufferedImageData extends AbstractImageData
{
    byte[] bytes;

    public BufferedImageData(String mimeType, byte[] bytes, long timestamp, int width, int height)
    {
	super( mimeType, timestamp, bytes.length, width, height );
	this.bytes = bytes;
    }

    public byte[] getBytes()
    { return bytes;}

    public InputStream getInputStream() throws IOException
    { return new ByteArrayInputStream( bytes ); }
}
