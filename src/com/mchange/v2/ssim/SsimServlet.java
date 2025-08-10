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

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import com.mchange.v1.io.InputStreamUtils;
import com.mchange.v2.net.LocalHostManager;
import com.mchange.v2.util.PatternReplacementMap;


public class SsimServlet extends HttpServlet
{
    public final static String PATTERN_REPLACEMENT_MAP_APPKEY = "com_mchange_v2_ssim_SsimServlet__patternReplacementMap";

    final static int DFLT_BROWSER_MAX_AGE = 3600; // seconds, neg means no-cache
    final static int DFLT_MAX_WIDTH       = 2000;
    final static int DFLT_MAX_HEIGHT      = 2000;
    final static int DFLT_CACHE_SIZE      = 50;  //50MB
    final static int DFLT_CULL_DELAY      = 300; //try to cull every five minutes

    final static int DFLT_MAX_SIMULTANEOUS_SCALES = 3;

    static LocalHostManager lhm;

    ServletContext sc;
    File cacheDir;

    ImageFinder imf;
    int scale_counter = 0;

    PatternReplacementMap patternReplacementMap = null;

    // MT: unchanging after init()
    int        browser_max_age         = DFLT_BROWSER_MAX_AGE; // seconds, neg means no-cache
    int        max_width               = DFLT_MAX_WIDTH;
    int        max_height              = DFLT_MAX_HEIGHT;
    int        max_simultaneous_scales = DFLT_MAX_SIMULTANEOUS_SCALES;
    boolean    open_relay              = false;
    boolean    never_relay             = false;
    String     myDomain                = null;
    String[]   allowDomains            = null; //all lower case
    String     baseUrl                 = null;
    String     baseResourcePath        = null;
    String     cacheControlHeaderValue = null;

    InetAddress localHostAddr; // only used for access checks, iff open_relay stays false
    // MT: end unchanging after init()

    public void init()
	throws ServletException
    { 
	this.sc = this.getServletContext();
	String browserMaxAgeStr     = this.getInitParameter( "browserMaxAge" );
	String cacheDirStr          = this.getInitParameter( "cacheDir" );
	String maxWidthStr          = this.getInitParameter( "maxWidth" );
	String maxHeightStr         = this.getInitParameter( "maxHeight" );
	String allowDomainsStr      = this.getInitParameter( "allowDomains" );
	String baseUrlStr           = this.getInitParameter( "baseUrl" );
	String baseResourcePathStr  = this.getInitParameter( "baseResourcePath" );
	String cacheSizeStr         = this.getInitParameter( "cacheSize" );
	String cullDelayStr         = this.getInitParameter( "cullDelay" );
	String maxConcurrencyStr    = this.getInitParameter( "maxConcurrency" );

	if (browserMaxAgeStr != null && browserMaxAgeStr.length() > 0) {
	    try { this.browser_max_age = Integer.parseInt( browserMaxAgeStr ); }
	    catch ( NumberFormatException e )
		{ throw new UnavailableException( "Could not parse browserMaxAge init param: " + browserMaxAgeStr ); }
	}

	if ( browser_max_age > 0 ) 
	    cacheControlHeaderValue = "max-age=" + browser_max_age + ", public";
	else
	    cacheControlHeaderValue = "no-cache";

	// figure out cache dir
	if (cacheDirStr != null)
	    cacheDir = new File( cacheDirStr.trim() );
	else
	    cacheDir = (File) sc.getAttribute( "javax.servlet.context.tempdir" );
	if (! cacheDir.isDirectory() || ! cacheDir.canWrite())
	    throw new UnavailableException("Cache directory: " + cacheDir + " does not exist, is not a directory, or is not writable!");
	else
	    System.err.println("SsimServlet: Using cache directory: " + cacheDir);

	// figure out baseUrl / path
	if (baseUrlStr != null && baseResourcePathStr != null)
	    throw new UnavailableException("It is illegal to specify both a baseUrl and a baseResourcePath Servlet init param.");	    
	else if (baseUrlStr != null)
	    {
		baseUrl = baseUrlStr.trim();
		if (baseUrl.endsWith("/"))
		    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
	    }
	else if (baseResourcePathStr != null)
	    baseResourcePath = baseResourcePathStr.trim();

	// security init params
	try { if (maxWidthStr != null) max_width = Integer.parseInt( maxWidthStr ); }
	catch ( NumberFormatException e )
	    { throw new UnavailableException( "Could not parse maxWidth init param: " + maxWidthStr ); }
	if ( max_width <= 0 ) max_width = Integer.MAX_VALUE;

	try { if (maxHeightStr != null) max_height = Integer.parseInt( maxHeightStr ); }
	catch ( NumberFormatException e )
	    { throw new UnavailableException( "Could not parse maxHeight init param: " + maxHeightStr ); }
	if ( max_height <= 0 ) max_height = Integer.MAX_VALUE;

	if (allowDomainsStr != null)
	    allowDomains = allowDomainsStr.trim().toLowerCase().split("\\s*,\\s*");
	else
	    allowDomains = new String[0];
	for (int i = 0; i < allowDomains.length; ++i)
	    {
		if ( "all".equalsIgnoreCase( allowDomains[i]) )
		    {
			open_relay = true;
			break;
		    }
		else if ("none".equalsIgnoreCase( allowDomains[i] ) )
		    {
			never_relay = true;
			break;
		    }
	    }
	if ( open_relay && never_relay )
	    throw new UnavailableException("allowDomains cannot include both 'all' and 'none'.");

	if (! open_relay)
	    {
		try
		    {
			localHostAddr = InetAddress.getLocalHost();
			String localHostName = localHostAddr.getCanonicalHostName();
			myDomain = subdomainFromHost( localHostName );
			if ( myDomain == null )
			    myDomain = "local";
		    }
		catch ( UnknownHostException e )
		    {
			System.err.println( "Couldn't look up localhost to permit within-domain relaying!" );
			e.printStackTrace();
			myDomain = null;
		    }
	    }

	//maxConcurrency stuff
	try { if (maxConcurrencyStr != null) max_simultaneous_scales = Integer.parseInt( maxConcurrencyStr ); }
	catch ( NumberFormatException e )
	    { throw new UnavailableException( "Could not parse maxConcurrency init param: " + maxConcurrencyStr ); }

	int cache_size = DFLT_CACHE_SIZE;
	int cull_delay = DFLT_CULL_DELAY;
	try { if (cacheSizeStr != null) cache_size = Integer.parseInt( cacheSizeStr ); }
	catch ( NumberFormatException e )
	    { throw new UnavailableException( "Could not parse cacheSize init param: " + cacheSizeStr ); }
	try { if (cullDelayStr != null) cull_delay = Integer.parseInt( cullDelayStr ); }
	catch ( NumberFormatException e )
	    { throw new UnavailableException( "Could not parse cullDelay init param: " + cullDelayStr ); }
	
	imf = new MyImageFinder( cache_size, cull_delay );
    }

    public void doGet(HttpServletRequest req, HttpServletResponse res)
	throws IOException, ServletException
    {
	String widthStr               = req.getParameter( "width" );
	String heightStr              = req.getParameter( "height" );
	String mimeType               = req.getParameter( "mimeType" );
	String imageUrlStr            = req.getParameter( "imageUrl" );
	String preserveAspectRatioStr = req.getParameter( "preserveAspectRatio" );

	int     width;
	int     height;
	boolean preserve_aspect_ratio;
	URL     imageUrl;

	if ( patternReplacementMap == null )
	    {
		patternReplacementMap = (PatternReplacementMap ) sc.getAttribute( PATTERN_REPLACEMENT_MAP_APPKEY );
		if (patternReplacementMap != null)
		    {
			System.err.println(this + 
					   ": Trusted PatternReplacementMap installed in application -- takes precedence over any baseURL " +
					   " or beseResourcePath set, and no security checks will be performed on replaced URLs!"); 
		    }
	    }

	try { width = (widthStr  != null ? Integer.parseInt( widthStr )  : -1); }
	catch ( Exception e )
	    { width = -1; }

	try { height = (heightStr != null ? Integer.parseInt( heightStr ) : -1); }
	catch ( Exception e )
	    { height = -1; }

	if (width > max_width || height > max_height)
	    {
		res.sendError( HttpServletResponse.SC_BAD_REQUEST, 
			       "Dimensions exceed maximum [max_width: " + max_width + ", max_height: " + max_height + ']' );
		return;
	    }


	String servletPath = req.getServletPath();
	String uid = null;
	boolean base_url_host = false;
	boolean explicitly_replaced_pattern = false;

	if (patternReplacementMap != null)
	    uid = patternReplacementMap.attemptReplace( servletPath );
	//System.err.println("uid after attemptReplace: " + uid);

	if (uid != null)
	    explicitly_replaced_pattern = true;
	else
	    {
		if ( imageUrlStr == null )
		    {
			if (baseUrl != null)
			    {
				if (considerAbsoluteUrl( baseUrl ))
				    {
					uid = baseUrl + servletPath;
					base_url_host = true;
				    }
				else
				    {
					//TODO: huh??? what was i thinking here???
					
					StringBuffer sb = req.getRequestURL();
					int doubleslash_index = sb.indexOf("//");
					int path_slash_index = sb.indexOf("/", doubleslash_index + 2);
					uid = sb.substring( 0, path_slash_index ) + servletPath;
				    }
			    }
			else if (baseResourcePath != null)
			    uid = baseResourcePath + servletPath;
			else
			    uid = servletPath;
		    }
		else
		    {
			if (never_relay)
			    {
				res.sendError( HttpServletResponse.SC_FORBIDDEN, 
					       "This Servlet is configured not to allow specification of 'imageURL' in the request." );
				return;
			    }
			else
			    uid = imageUrlStr.trim();
		    }
	    }
	//System.err.println("using uid: " + uid);

	if ( !open_relay && !explicitly_replaced_pattern && considerAbsoluteUrl( uid ) && !base_url_host && !checkAccessibleHost( uid, req.getHeader("Host") ) )
	    {
		res.sendError( HttpServletResponse.SC_FORBIDDEN, 
			       "Forwarding items from the URL " + uid + " is not permitted." );
		return;
	    }

	// don't set unspecified mimeTypes to a default based on the suffix of the uid
	// because if we leave the mimeType unspecified, the finder will use that default
	// type if it can write it, or else it will pick some other mime type that it can
	// write.

	if (mimeType != null)
	    mimeType=mimeType.trim();

	if ( preserveAspectRatioStr != null )
	    preserveAspectRatioStr = preserveAspectRatioStr.trim();
	
	// if preserveAspectRatioStr == null, this will go to false
	preserve_aspect_ratio =  Boolean.valueOf( preserveAspectRatioStr ).booleanValue();

	try
	    {
		ImageData data = null;
		try
		    { 
			enterScale();
			data = imf.find( uid, mimeType, width, height, preserve_aspect_ratio ); 
		    }
		finally
		    { exitScale(); }

		res.setHeader("Cache-Control", cacheControlHeaderValue);
		res.setContentType( data.getMimeType() );
		int cl = data.getContentLength();
		if ( cl >= 0 )
		    res.setContentLength( cl );
		InputStream is = null;
		try
		    {
			is = data.getInputStream();
			OutputStream os = res.getOutputStream();
			for (int b = is.read(); b >= 0; b = is.read())
			    os.write( b );
			os.flush();
		    }
		finally
		    { InputStreamUtils.attemptClose( is ); }
	    }
	catch ( InterruptedException e )
	    {
		e.printStackTrace();
		throw new ServletException( e );
	    }
	catch ( SsimException e )
	    {
		e.printStackTrace();
		throw new ServletException( e );
	    }
    }

    public void destroy()
    {
	try
	    { imf.close(); }
	catch( SsimException e )
	    { e.printStackTrace(); }
    }

    private static boolean considerAbsoluteUrl( String uid )
    { return (uid.indexOf(':') > 0); }

    private boolean checkAccessibleHost( String uid, String reqHost ) throws MalformedURLException, SocketException, ServletException
    {
	URL absUrl = new URL( uid );
	String host = absUrl.getHost().toLowerCase();
	//System.err.println( "accessible host? " + host );
	if ( host.equals("localhost") || host.equals("127.0.0.1") )
	    return true;
	if ( myDomain != null && host.endsWith( myDomain ) )
	    return true;
	if ( reqHost != null)
	    {
		if (! isValidToLocalHost( reqHost ))
		    throw new ServletException("Invalid request to virtual host '"+ reqHost + "' which is not a valid name for server!");

		if (reqHost.equals( host )) 
		    return true;

		String reqsubdomain = subdomainFromHost( reqHost );
		if (host.endsWith( reqsubdomain ))
		    return true;
	    }
	for (int i = 0, len = allowDomains.length; i < len; ++i)
	    if ( host.endsWith( allowDomains[i] ) )
		return true;
 	try
 	    {
		if (localHostAddr.equals( InetAddress.getByName(host) ))
		    return true;
 	    }
 	catch (UnknownHostException e)
 	    { 
		System.err.println(this + ": Denied serving image from unknown host " + host);
	    }
	return false;
    }

    private synchronized void enterScale() throws InterruptedException
    {
	while ( scale_counter == max_simultaneous_scales )
	    this.wait();
	++scale_counter;
    }

    private synchronized void exitScale()
    {
	--scale_counter;
	this.notifyAll();
    }

    class MyImageFinder extends AbstractImageFinder
    {
	MyImageFinder( int max_size, int cull_delay )
	{ super( new DirectoryBasedPersistentStore( cacheDir, max_size, cull_delay ) ); }

	protected URL urlForUid( final String uid ) throws Exception
	{
	    // System.err.println("XXXX urlForUid !!!");
	    if ( considerAbsoluteUrl( uid ) )
		return new URL( uid );
	    else
		return sc.getResource( uid );
	}

	// we cache even unmodified images if they are not local
	protected boolean cacheUnmodified( String uid ) throws Exception
	{
	    return considerAbsoluteUrl( uid ) && (!uid.startsWith("file:"));
	}
    }

    static String subdomainFromHost( String fqhostname )
    {
	int first_dot = fqhostname.indexOf('.');
	if ( first_dot > 0 )
	    return fqhostname.substring( first_dot + 1);
	else
	    return null;
    }

    private static synchronized boolean isValidToLocalHost( String name ) throws SocketException
    {
	// defer this as late as possible, so out network interaces
	// are likely up. we never update() the local host manager,
	// presuming our relevant network interfaces are unchaning 
	// by this point
	if ( lhm == null )
	    lhm = new LocalHostManager();
	return lhm.isLocalHostName( name );
    }
}



//     URL        baseUrl;


// 	try
// 	    {
// 		if (baseUrlStr == null && baseResourcePath == null)
// 		    baseUrl = sc.getResource("/");
// 		else if (baseUrlStr != null && baseResourcePath == null)
// 		    baseUrl = new Url( baseUrlStr.trim() );
// 		else if (baseUrlStr == null && baseResourcePath != null)
// 		    baseUrl = sc.getResource( baseResourcePath.trim() );
// 		else
// 		    throw new UnavailableException("It is illegal to specify both a baseUrl and a baseResourcePath Servlet init param.");
// 	    }
// 	catch ( MalformedException e )
// 	    { throw new UnavailableException( "Bad baseUrl: " + baseUrlStr ); }

