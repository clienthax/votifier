/*
 * Copyright (C) 2012 Vex Software LLC
 * This file is part of Votifier.
 * 
 * Votifier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Votifier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Votifier.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vexsoftware.votifier;

import com.vexsoftware.votifier.crypto.KeyCreator;
import com.vexsoftware.votifier.crypto.RSAIO;
import com.vexsoftware.votifier.crypto.RSAKeygen;
import com.vexsoftware.votifier.net.VoteInboundHandler;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.net.protocol.VotifierGreetingHandler;
import com.vexsoftware.votifier.net.protocol.VotifierProtocolDifferentiator;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.security.Key;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

/**
 * The main Votifier plugin class.
 * 
 * @author Blake Beaupain
 * @author Kramer Campbell
 */
@Mod(name = "Votifier", serverSideOnly = true, acceptableRemoteVersions = "*", modid = "votifier", version = "2.0")
public class Votifier {

	/** The logger instance. */
	private static org.apache.logging.log4j.Logger LOG;

	/** Log entry prefix */
	private static final String logPrefix = "[Votifier] ";

	/** The Votifier instance. */
	private static Votifier instance;

	/** The server channel. */
	private Channel serverChannel;

	/** The event group handling the channel. */
	private NioEventLoopGroup serverGroup;

	/** The RSA key pair. */
	private KeyPair keyPair;

	/** Debug mode flag */
	private boolean debug;

	/** Keys used for websites. */
	private Map<String, Key> tokens = new HashMap<>();

	/** Folder containing config */
	private File modConfigFolder;

	public File getDataFolder() {
		return modConfigFolder;
	}

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		Votifier.instance = this;
		LOG = event.getModLog();
		modConfigFolder = new File(event.getModConfigurationDirectory(), "Votifier");
		System.out.println(modConfigFolder.getAbsolutePath());
	}

	@Mod.EventHandler
	public void onInit(FMLInitializationEvent event) throws IOException {

		// Handle configuration.
		if (!getDataFolder().exists()) {
			getDataFolder().mkdir();
		}

		File config = new File(getDataFolder() + "/config.yml");
		ConfigurationLoader loader = YAMLConfigurationLoader.builder().setFile(config).build();
		ConfigurationNode cfg = loader.load();
		File rsaDirectory = new File(getDataFolder() + "/rsa");

		/*
		 * Use IP address from server.properties as a default for
		 * configurations. Do not use InetAddress.getLocalHost() as it most
		 * likely will return the main server address instead of the address
		 * assigned to the server.
		 */
		String hostAddr = MinecraftServer.getServer().getHostname();
		if (hostAddr == null || hostAddr.length() == 0)
			hostAddr = "0.0.0.0";

		/*
		 * Create configuration file if it does not exists; otherwise, load it
		 */
		if (!config.exists()) {
			try {
				// First time run - do some initialization.
				LOG.info("Configuring Votifier for the first time...");

				// Initialize the configuration file.
				config.createNewFile();

				cfg.getNode("host").setValue(hostAddr);
				cfg.getNode("port").setValue(8192);
				cfg.getNode("debug").setValue(false);

				/*
				 * Remind hosted server admins to be sure they have the right
				 * port number.
				 */
				LOG.info("------------------------------------------------------------------------------");
				LOG.info("Assigning Votifier to listen on port 8192. If you are hosting Forge on a");
				LOG.info("shared server please check with your hosting provider to verify that this port");
				LOG.info("is available for your use. Chances are that your hosting provider will assign");
				LOG.info("a different port, which you need to specify in config.yml");
				LOG.info("------------------------------------------------------------------------------");

				String token = TokenUtil.newToken();
				ConfigurationNode tokenSection = cfg.getNode("tokens");
				tokenSection.getNode("default").setValue(token);
				LOG.info("Your default Votifier token is " + token + ".");
				LOG.info("You will need to provide this token when you submit your server to a voting");
				LOG.info("list.");
				LOG.info("------------------------------------------------------------------------------");

				loader.save(cfg);
			} catch (Exception ex) {
				LOG.log(Level.FATAL, "Error creating configuration file", ex);
				gracefulExit();
				return;
			}
		} else {
			// Load configuration.
			cfg = loader.load();
		}

		/*
		 * Create RSA directory and keys if it does not exist; otherwise, read
		 * keys.
		 */
		try {
			if (!rsaDirectory.exists()) {
				rsaDirectory.mkdir();
				keyPair = RSAKeygen.generate(2048);
				RSAIO.save(rsaDirectory, keyPair);
			} else {
				keyPair = RSAIO.load(rsaDirectory);
			}
		} catch (Exception ex) {
			LOG.fatal("Error reading configuration file or RSA tokens", ex);
			gracefulExit();
			return;
		}

		// Load Votifier tokens.
		ConfigurationNode tokenSection = cfg.getNode("tokens");

		if (tokenSection != null) {
			Map<Object, ? extends ConfigurationNode> websites = tokenSection.getChildrenMap();
			for (Map.Entry<Object, ? extends ConfigurationNode> website : websites.entrySet()) {
				tokens.put((String) website.getKey(), KeyCreator.createKeyFrom(website.getValue().getString()));
				LOG.info("Loaded token for website: " + website.getKey());
			}
		} else {
			LOG.warn("No websites are listed in your configuration.");
		}

		// Initialize the receiver.
		String host = cfg.getNode("host").getString(hostAddr);
		int port = cfg.getNode("port").getInt(8192);
		debug = cfg.getNode("debug").getBoolean(false);
		if (debug)
			LOG.info("DEBUG mode enabled!");

		serverGroup = new NioEventLoopGroup(1);

		new ServerBootstrap()
				.channel(NioServerSocketChannel.class)
				.group(serverGroup)
				.childHandler(new ChannelInitializer<NioSocketChannel>() {
					@Override
					protected void initChannel(NioSocketChannel channel) throws Exception {
						channel.attr(VotifierSession.KEY).set(new VotifierSession());
						channel.pipeline().addLast("greetingHandler", new VotifierGreetingHandler());
						channel.pipeline().addLast("protocolDifferentiator", new VotifierProtocolDifferentiator());
						channel.pipeline().addLast("voteHandler", new VoteInboundHandler());
					}
				})
				.bind(host, port)
				.addListener(new ChannelFutureListener() {
					@Override
					public void operationComplete(ChannelFuture future) throws Exception {
						if (future.isSuccess()) {
							serverChannel = future.channel();
							LOG.info("Votifier enabled.");
						} else {
							LOG.fatal("Votifier was not able to bind to " + future.channel().localAddress(), future.cause());
						}
					}
				});
	}

	@Mod.EventHandler
	public void onShutdown(FMLServerStoppingEvent event) {
		// Shut down the network handlers.
		if (serverChannel != null)
			serverChannel.close();
		serverGroup.shutdownGracefully();
		LOG.info("Votifier disabled.");
	}

	private void gracefulExit() {
		LOG.error("Votifier did not initialize properly!");
	}

	/**
	 * Gets the instance.
	 * 
	 * @return The instance
	 */
	public static Votifier getInstance() {
		return instance;
	}

	/**
	 * Get the logger
	 */
	public Logger getLogger() {
		return LOG;
	}

	/**
	 * Gets the keyPair.
	 *
	 * @return The keyPair
	 */
	public KeyPair getKeyPair() {
		return keyPair;
	}

	public boolean isDebug() {
		return debug;
	}

	public Map<String, Key> getTokens() {
		return tokens;
	}


	public String getVersion() {
		return "2.0";
	}
}
