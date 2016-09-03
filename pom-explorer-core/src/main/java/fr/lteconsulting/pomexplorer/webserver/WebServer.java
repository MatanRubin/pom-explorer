package fr.lteconsulting.pomexplorer.webserver;

import static io.undertow.servlet.Servlets.servlet;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channel;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;

import org.glassfish.jersey.servlet.ServletContainer;

import fr.lteconsulting.pomexplorer.Client;
import fr.lteconsulting.pomexplorer.rest.JerseyConfig;
import fr.lteconsulting.pomexplorer.rest.TestServlet;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.StreamSourceFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;

public class WebServer
{
	private final static String DATA_FILE_PREFIX_URL = "/files/";
	private final static String DATA_FILE_STORE_DIR = "served-data";

	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final XWebServer xWebServer;

	private final Map<Integer, Client> clients = new HashMap<>();

	public WebServer( XWebServer xWebServer )
	{
		this.xWebServer = xWebServer;
	}

	private Client getClient( Channel channel )
	{
		return clients.get( System.identityHashCode( channel ) );
	}

	private String getQueryParameter( HttpServerExchange exchange, String name )
	{
		Deque<String> de = exchange.getQueryParameters().get( name );
		if( de == null || de.isEmpty() )
			return null;

		assert de.size() == 1;
		String value = de.getFirst();
		return value;
	}

	/**
	 * Stores a file to make it servable by the web server
	 * 
	 * @param fileName
	 *            the file name
	 * @return The URL to get the file
	 */
	public Writer pushFile( String fileName )
	{
		try
		{
			File dataDir = new File( DATA_FILE_STORE_DIR );
			dataDir.mkdirs();

			FileWriter fileWriter = new FileWriter( Paths.get( DATA_FILE_STORE_DIR, fileName ).toString() );
			return fileWriter;
		}
		catch( IOException e )
		{
			e.printStackTrace();
		}

		return null;
	}

	public String getFileUrl( String fileName )
	{
		return DATA_FILE_PREFIX_URL + fileName;
	}

	public void start( int port )
	{
		PathHandler pathHandler = new PathHandler();

		// web app static files
		String localDirectoryName = "pomexplorer-ui";
		File servedDirectory = new File( localDirectoryName );
		if( servedDirectory.exists() && servedDirectory.isDirectory() )
		{
			pathHandler.addPrefixPath( "/", new ResourceHandler( new FileResourceManager( servedDirectory, 0 ) ) );
			System.out.println( "serving from local " + localDirectoryName + " directory" );
		}
		else
		{
			ResourceManager manager = new ClassPathResourceManager( getClass().getClassLoader(), "pomexplorer-ui" );
			pathHandler.addPrefixPath( "/", new ResourceHandler( manager ).addWelcomeFiles( "index.html" ) );
		}

		// exported data files
		File dataDir = new File( DATA_FILE_STORE_DIR );
		dataDir.mkdirs();
		pathHandler.addPrefixPath( DATA_FILE_PREFIX_URL, new ResourceHandler( new PathResourceManager( dataDir.toPath(), 0 ) ) );

		// graph query
		pathHandler.addExactPath( "/graph", new HttpHandler()
		{
			@Override
			public void handleRequest( HttpServerExchange exchange ) throws Exception
			{
				String result = xWebServer.onGraphQuery( getQueryParameter( exchange, "session" ), getQueryParameter( exchange, "graphQueryId" ) );
				exchange.getResponseSender().send( result );
			}
		} );

		// websocket communication
		pathHandler.addPrefixPath( "/ws", Handlers.websocket( new WebSocketConnectionCallback()
		{
			@Override
			public void onConnect( WebSocketHttpExchange exchange, WebSocketChannel channel )
			{
				Client client = new Client( System.identityHashCode( channel ), channel );
				clients.put( client.getId(), client );

				xWebServer.onNewClient( client );

				channel.getReceiveSetter().set( new AbstractReceiveListener()
				{
					@Override
					protected void onFullTextMessage( final WebSocketChannel channel, final BufferedTextMessage message )
					{
						executor.submit( () -> xWebServer.onWebsocketMessage( getClient( channel ), message.getData() ) );
					}

					@Override
					protected void onClose( WebSocketChannel webSocketChannel, StreamSourceFrameChannel channel ) throws IOException
					{
						super.onClose( webSocketChannel, channel );

						xWebServer.onClientLeft( getClient( channel ) );
						clients.remove( System.identityHashCode( channel ) );
					}
				} );

				channel.resumeReceives();
			}
		} ) );

		// JAX-RS web services
		try
		{
			DeploymentInfo servletBuilder = Servlets.deployment();

			servletBuilder
					.setClassLoader( WebServer.class.getClassLoader() )
					.setContextPath( "/api" )
					.setDeploymentName( "api.war" )
					.addServlets(
							servlet( "testServlet", TestServlet.class )
									.addMapping( "/test/*" ),
							servlet( "jerseyServlet", ServletContainer.class )
									.setLoadOnStartup( 1 )
									.addInitParam( "javax.ws.rs.Application", JerseyConfig.class.getName() )
									.addMapping( "/*" ) );

			DeploymentManager manager = Servlets.defaultContainer().addDeployment( servletBuilder );
			manager.deploy();
			pathHandler.addPrefixPath( "/api", manager.start() );
		}
		catch( ServletException e )
		{
			e.printStackTrace();
		}

		Undertow server = Undertow
				.builder()
				.addHttpListener( port, "0.0.0.0" )
				.setHandler( pathHandler )
				.build();
		
		server.start();
	}
}