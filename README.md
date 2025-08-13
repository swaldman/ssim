# ssim (Server Side Image Manager)

_**a kind of ancient (2003) library for on-the-fly rescaling &amp; caching of images on a web server**_

---

## Table of Contents

* [Basics](#basics)
* [Prerequisites](#prerequisites)
* [Using SSIM as a Servlet in a Java Web Application](#using-ssim-as-a-servlet-in-a-java-web-application)
* [Using SSIM to manage images for a non\-Java website](#using-ssim-to-manage-images-for-a-non-java-website)
* [SSIM as a Stand\-Alone Service](#ssim-as-a-stand-alone-service)
* [Using SSIM to manage both web\-app images and certain external images](#using-ssim-to-manage-both-web-app-images-and-certain-external-images)
* [Appendix A: Initialization Parameters](#appendix-a-initialization-parameters)
* [Appendix B: Request Parameters](#appendix-b-request-parameters)
* [Appendix C: Sample web\.xml](#appendix-c-sample-webxml)
  
## Basics

Server-Side Image Manager is a library designed to simplify the management of images on websites. In particular, webites often require that the same basic image be made available in multiple sizes. Common examples include icons or logos, which may be dispersed throught a website in many sizes, or photographs which are presented in thumbnail form, and then are made available at multiple resolutions.

There are many approaches to dealing with the problem of multiple scaled images, but none of them are great. Lazy webmasters just supply a large image and use HTML width and height attributes to force clients to scale the images, but this is inefficient: to display even an icon or thumbnail, the client has to download the oversized image and rescale it, wasting bandwith and client CPU. More diligent webmasters prescale the image by hand to each desired size. But, this approach is a pain in the ass, a maintenance problem — every time an image changes, or a new size is desired, the webmaster must remember to reproduce all required scaled instances.

Server-Side Image Manager allows webmasters to get the performance and efficiency advantages of prescaling without the maintenance problems. SSIM transforms images into dynamic rather than static resources. For example, an HTML page might reference an image tags that look like any of the following:

* `<img src="cool_pic.jpg?width=50">` &mdash; references `cool_pic.jpg`, scaled to a width of 50 pixes and its natural, aspect-ratio-preserving height.

* `<img src="cool_pic.jpg?height=50">` &mdash; references `cool_pic.jpg`, scaled to a height of 50 pixes and its natural, aspect-ratio-preserving width.

* `<img src="cool_pic.jpg?width=200&height=300">` &mdash; references `cool_pic.jpg`, scaled to a width of 200 pixels and a height of 300 pixels, even if this results in the image being distorted.

* `<img src="cool_pic.jpg?width=200&height=300&preserveAspectRatio=true">` &mdash; references `cool_pic.jpg`, scaled to the maximum size possible that will fit within a bounding box of width 200 and height 300, but that preserves the image's natural aspect ratio.

* `<img src="imageservlet?imageUrl=http://foo.booboo.com/faraway_pic.jpg?width=200">` &mdash; references faraway_pic.jpg on some other server, scaled to a width of 200 pixels

Server-side Image Manager caches the images it generates and serves, so, after the first request for a scaled version, users see performance similar to static images. Great care has been taken in the development of the library to ensure maximum concurrent access to images, while making sure that clients never see incomplete or broken images while images are being scaled and cached.

Server-side Image Manage can be used as a Servlet in java-based web applications, or, in combination with a standalone servlet environment, as a service that non-Java based websites can use to manage their images.

## Prerequisites

SSIM requires a that a Java Virtual Machine, version 1.8 or higher, and a Java Servlet environment, be the server that will manage your images. Users who wish to run SSIM in combination with a non-Java webserver can do so by setting up the free Tomcat server as a standalone service.

(For older JVMs, look back to older version of this library.)

## Using SSIM as a Servlet in a Java Web Application

If you plan to use Server-Side Image Manager as a servlet, you should already be familiar with the how to deploy Java Servlets and web applications. Nothing more will be required of you than adding SSIM's jar file to your web-application, and configuring the servlet in your web.xml file. See the [sample web.xml in Appendix C](#appendix-c-sample-webxml) below.

The classname of the SSIM's servlet is com.mchange.v2.ssim.SsimServlet. You'll need to be sure that all requests to images that should be handled by the servlet get mapped to the servlet, usually by providing suffix url-mappings such as *.gif, *.jpg, etc. See the [sample](#appendix-c-sample-webxml)

The servlet accepts a bunch of initialization parameters, which are described in detail in [Appendix A](#appendix-a-initialization-parameters). While all initialization parameters are optional, you'll probably want to set at least [cacheDir](#appendix-a-initialization-parameters), since it defaults to using the web applications temporary directory, which may not be preserved between web application restarts.

## Using SSIM to manage images for a non-Java website

You can plug SSIM into apache or other webservers and let it manage your images for you. You'll have to figure out how to run Java servlets and web applications in combination with your webserver. One common configuration is to use the Apache webserver, Apache Jakarta Tomcat, and mod_jk or mod_jk2. You'll also need to install Apache Ant.

To set this up, make a clone or branch of this distributionm and modify the file `web-xml-template.mill`. 

* Uncomment and fill in values for `InitParams in that file.
  (Quoted strings or unquoted integer values are fine.) 
  
* Change the `webAppName` if you like. (This usually becomes the "context path" for the application, i.e. if `webAppName`
  is `ssim`, the URLs of the images managed by the application will begin with `/ssim`.)

The crucial thing is to get the initialization parameters right. Let's say you have an existing website, whose document root is `/usr/local/web`, and you want to make its images scalable. You'd edit `InitParams` to look something like this:

```scala
val InitParams : Map[String,Any] = Map(
  // "allowDomains" -> ???,
  // "baseResourcePath" -> ???,
  "baseUrl" -> "file:///usr/local/web/",
  // "browserMaxAge" -> ???,
  // "cacheDir" -> ???,
  // "cacheSize" -> ???,
  // "cullDelay" -> ???,
  // "maxWidth" -> ???,
  // "maxHeight" -> ???,
)
```

You might also want to supply other init param values! See [Appendix A](#appendix-a-initialization-parameters) below.

After modifying `web-xml-template.mill` to taste, you'd run, from the top-level of this distribution

```plaintext
$ ./mill war
```

Then, in the directory `out/war.dest, you'll find the file `<webAppName>.war`. That's your old-school Java web application!

Deploy it to Tomcat or whatever and you are good to go. 

If your web application is called `ssim`, so the war file is `ssim.war`, then for images you'd like scaled, you can use
tags like `<img src="/ssim/icons/logo.gif?width=200">`.

> [!NOTE]
> If the web application runs under a different virtual host than your site, 
> you'll need an absolute URL, e.g. `<img src="https://apphost.mydomain.io/ssim/icons/logo.gif?width=200">`
>
> If you've [manually deployed the webapp](https://octopus.com/blog/defining-tomcat-context-paths) under a
> different context path, well, then you'll use that context path rather than `ssim`, which might just be `/`

## SSIM as a Stand-Alone Service

You can use SSIM as a "standalone" image server, put your images in its care, and let your non-Java website refer to the images via absolute URLs.
It'ss the same deployment as the one [just described](#ssim-as-a-stand-alone-service).

## Using SSIM to manage both web-app images and certain external images

**[Advanced]**  It is frequently useful to have images both internal to a Java web application and external to the application's war file be managed by SSIM. For example, if you want to write a photoalbum application, you might wish to let the images be stored in some easily modifiable external directory, but also have images internal to the web-application itself. You can do this by defining a web-application-scoped variable (i.e. a ServletContext attribute) under the name `com_mchange_v2_ssim_SsimServlet__patternReplacementMap`, which must be of type [`com.mchange.v2.util.PatternReplacementMap`](https://javadoc.io/doc/com.mchange/mchange-commons-java/latest/com/mchange/v2/util/PatternReplacementMap.html). 

If you do this, `SsimServlet` will check each requests path (URI) against a list of regular expression. If the path matches, SSIM substitute the replacement path you specify for the original. The replacement paths may depend upon the originally requested path, by virtue of the common regular expression convention that allows regexes to contain parenthesized subgroupings, and regex replacement expressions to refer to those groupings as $1 for the first subgrouping, $2 for the second, and $0 for the whole matching expression. The replacements should be absolute URLs. They may be file: URLs.

Any path that resolves to a URL via the `PatternReplacementMap` will be retrieved from this URL. **These paths are considered trusted &mdash; no further security checks will be performed &mdash; and they take precedence over any baseResourcePath or baseUrl you may have set.**

## Appendix A: Initialization Parameters

* `allowDomains` &mdash; A comma separated list of domains from which SSIM should be willing to fetch, scale, and reserve images, when requests contain an explicit imageUrl parameter. If not specified, only imageUrls from the same subdomain as the request, or to localhost if you are running locally, will be allowed. If set to the special value `all`, the Servlet will be allowed to retrieve and scale images from any URL. If set to the special value `none` specifying imageUrl as a request parameter is forbidden will result in an error.

* `baseResourcePath` &mdash; A path, relative to the root of the web-application in which an SSIMServlet is running, that should be prepended to pathInfo in finding source images (when no explicit imageUrl is specified). Effectively defaults to / when not specified. **Only one of baseResourcePath and baseUrl can be explicitly specified.**

* `baseUrl` &mdash; A url that should be prepended to pathInfo in finding source images (when no explicit imageUrl is specified). This URL can be a file: URL, allowing you to keep your source images separate from your web application. Effectively defaults to the return value of `request.getResource("/")` when not specified. **Only one of baseResourcePath and baseUrl can be explicitly specified.**

* `browserMaxAge` &mdash; Controls how long, in seconds, browsers should be instructed to cache scaled images on the client side (using the `Cache-Control` HTTP header). Defaults to 3600 seconds. If less than or equal to zero, the browser will be instructed not to cache at all (via  `Cache-Control: no-cache`).

* `cacheDir` &mdash; The absolute path to a directory where SSIM can put its image cache. It is strongly recommended that you supply this parameter, as it defaults to the web application's temporary directory, which may not be retained between web-app restarts. _**Note that this directory must exist and be writable by the Java servlet container (e.g. tomcat).**_

* `cacheSize` &mdash; The maximum size (in megabytes) that SSIM should permit for its scaled image cache. SSIM will cull least-recently-used images when the cache size exceeds this value. Defaults to 50. If less than or equal to 0, the cache will never be culled cache size will unlimited.

* `cullDelay` &mdash; The number of seconds SSIM should wait between checks to see if the cache has exceeded the cache size and must be culled. Defaults to 300 (five minutes). If less than or equal to 0, the cache will never be culled cache size will unlimited.

* `maxConcurrency` &mdash; The maximum number of images SSIM will simultaneously attempt to scale. Defaults to 3. If this value is too high, SSIM may simultaneously attempt to scale many large images, and provoke `OutOfMemoryError`s.

* `maxWidth` &mdash; The maximum width (in pixels) that clients can request images scale to. Defaults to 2000. If less than or equal to zero, SSIM will scale images to unlimited widths.

* `maxHeight` &mdash; The maximum height (in pixels) that clients can request images scale to. Defaults to 2000. If less than or equal to zero, SSIM will scale images to unlimited heights.

## Appendix B: Request Parameters

* `width` &mdash; The width to which an image should be scaled. If supplied without height the image will scale to this width and while maintaining its natural aspect ratio. If height is supplied as well, the image may be distorted, unless preserveAspectRatio is also supplied, in which case the image will scale as large as possible up to a maximum of this width, but no wider than the natural aspect ratio would permit given an accompanying height constraint.

* `height` &mdash; The height to which an image should be scaled. If supplied without width the image will scale to this height and while maintaining its natural aspect ratio. If width is supplied as well, the image may be distorted, unless preserveAspectRatio is also supplied as true, in which case the image will scale as large as possible up to a maximum of this height, but no wider than the natural aspect ratio would permit given an accompanying width constraint.

* `imageUrl` &mdash; The full URL (often URL encoded) of the original image to be scaled. Rather than finding the image to scale based on the pathInfo of the request, SSIM can accept an explicit image URL, which needn't be on the same server as the SSIMServlet. The [`allowDomains`()] initialization parameters determines whether or not SSIM will be permitted to scale and serve a foreign image.

* `mimeType` &mdash; **[Experimental]** SSIM can not only scale images, but can also interconvert between image types. For example, if you have an image in PNG format, but you wish to make it available to old or crippled browsers that don't support this format, you might use a url like `myImage.png?mimeType=image/jpeg` (or in URL-encoded form `myImage.png?mimeType=image%2fjpeg`) which would cause the image to be converted on the fly to JPEG format, and for the converted image to be cached for future use. Mime-type interconversions and scaling can be combined freely.

* `preserveAspectRatio` &mdash; If this parameter set to true, than any width and height request parameters are treated as defining a bounding-box, within which the image will be scaled to the maximum possible size that retains the natural aspect ratio of the image.

## Appendix C: Sample web.xml

```xml
<?xml version="1.0" encoding="ISO-8859-1"?>

<!DOCTYPE web-app
  PUBLIC "-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN"
  "http://java.sun.com/dtd/web-app_2_3.dtd">

<web-app>
	<display-name>ssim-example</display-name>

	<servlet>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <servlet-class>com.mchange.v2.ssim.SsimServlet</servlet-class>
	  <init-param>
            <param-name>cacheDir</param-name>
            <param-value>/web/tmp/photo.mchange.com</param-value>
	  </init-param>
	</servlet>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.jpg</url-pattern>
	</servlet-mapping>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.JPG</url-pattern>
	</servlet-mapping>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.jpeg</url-pattern>
	</servlet-mapping>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.JPEG</url-pattern>
	</servlet-mapping>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.gif</url-pattern>
	</servlet-mapping>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.GIF</url-pattern>
	</servlet-mapping>

	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.png</url-pattern>
	</servlet-mapping>
	
	<servlet-mapping>
	  <servlet-name>imageScaleServlet</servlet-name>
	  <url-pattern>*.PNG</url-pattern>
	</servlet-mapping>

</web-app>
```











