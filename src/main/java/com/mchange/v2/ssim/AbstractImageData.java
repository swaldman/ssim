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

abstract class AbstractImageData extends AbstractImageSpec implements ImageData
{
    int    content_length;

    public AbstractImageData(String mimeType, long timestamp, int content_length, int width, int height)
    {
	super( mimeType, timestamp, width, height );
	this.content_length = content_length;
    }

    public AbstractImageData(String mimeType, long timestamp, int content_length)
    { this( mimeType, timestamp, content_length, -1, -1 ); }

    public AbstractImageData(String mimeType, long timestamp)
    { this( mimeType, timestamp, -1); }

    public abstract InputStream getInputStream() throws IOException;

    public int getContentLength()
    { return content_length; }
}
