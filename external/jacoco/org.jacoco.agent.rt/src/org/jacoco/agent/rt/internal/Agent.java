/*******************************************************************************
 * Copyright (c) 2009, 2021 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.Callable;

import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.internal.output.FileOutput;
import org.jacoco.agent.rt.internal.output.IAgentOutput;
import org.jacoco.agent.rt.internal.output.NoneOutput;
import org.jacoco.agent.rt.internal.output.TcpClientOutput;
import org.jacoco.agent.rt.internal.output.TcpServerOutput;
import org.jacoco.core.JaCoCo;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.AbstractRuntime;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.AgentOptions.OutputMode;
import org.jacoco.core.runtime.RuntimeData;

/**
 * The agent manages the life cycle of JaCoCo runtime.
 */
public class Agent implements IAgent {

	/**
	 * Returns a global instance which is already started. If the method is
	 * called the first time the instance is created with the given options.
	 *
	 * @param options
	 *            options to configure the instance
	 * @return global instance
	 */
	public static synchronized Agent getInstance(final AgentOptions options)
			throws Exception {
		// BEGIN android-change
		return getInstance(options, new RuntimeData());
		// END android-change
	}

	// BEGIN android-change
	/**
	 * Returns a global instance which is already started, reusing an existing set of runtime
	 * data. If the method is called the first time the instance is created with the given
	 * options.
	 * 
	 * @param options
	 *            options to configure the instance
	 * @param data
	 *            the runtime data to reuse
	 * @return global instance
	 */
	public static synchronized Agent getInstance(final AgentOptions options, RuntimeData data)
			throws Exception {
		final Agent agent = new Agent(options, IExceptionLogger.SYSTEM_ERR, data);
		agent.startup();
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				agent.shutdown();
			}
		});
		return agent;
	}
	// END android-change

	// BEGIN android-change
	/**
	 * Returns a global instance which is already started. If an agent has not
	 * been initialized then one will be created via {@link Offline#createAgent()}.
	 * This will capture any data written via {@link Offline#getProbes} prior to
	 * this call, but not subsequently.
	 * 
	 * @return global instance
	 * @throws IllegalStateException
	 *             if no Agent has been started yet
	 */
	// END android-change
	public static synchronized Agent getInstance() throws IllegalStateException {
		// BEGIN android-change
		// throw new IllegalStateException("JaCoCo agent not started.");
		return Offline.createAgent();
		// END android-change
	}

	private final AgentOptions options;

	private final IExceptionLogger logger;

	private final RuntimeData data;

	private IAgentOutput output;

	private Callable<Void> jmxRegistration;

	/**
	 * Creates a new agent with the given agent options.
	 *
	 * @param options
	 *            agent options
	 * @param logger
	 *            logger used by this agent
	 */
	Agent(final AgentOptions options, final IExceptionLogger logger) {
		// BEGIN android-change
		this(options, logger, new RuntimeData());
		// END android-change
	}

	// BEGIN android-change
	/**
	 * Creates a new agent with the given agent options, reusing the given runtime data.
	 *
	 * @param options
	 *            agent options
	 * @param logger
	 *            logger used by this agent
	 * @param data
	 *            the runtime data to reuse
	 */
	private Agent(final AgentOptions options, final IExceptionLogger logger, RuntimeData data) {
		this.options = options;
		this.logger = logger;
		this.data = data;
	}
	// END android-change

	/**
	 * Returns the runtime data object created by this agent
	 *
	 * @return runtime data for this agent instance
	 */
	public RuntimeData getData() {
		return data;
	}

	/**
	 * Initializes this agent.
	 *
	 * @throws Exception
	 *             in case something cannot be initialized
	 */
	public void startup() throws Exception {
		try {
			String sessionId = options.getSessionId();
			if (sessionId == null) {
				sessionId = createSessionId();
			}
			data.setSessionId(sessionId);
			output = createAgentOutput();
			output.startup(options, data);
			if (options.getJmx()) {
// BEGIN android-change
//				jmxRegistration = new JmxRegistration(this);
// END android-change
			}
		} catch (final Exception e) {
			logger.logExeption(e);
			throw e;
		}
	}

	/**
	 * Shutdown the agent again.
	 */
	public void shutdown() {
		try {
			if (options.getDumpOnExit()) {
				output.writeExecutionData(false);
			}
			output.shutdown();
			if (jmxRegistration != null) {
				jmxRegistration.call();
			}
		} catch (final Exception e) {
			logger.logExeption(e);
		}
	}

	/**
	 * Create output implementation as given by the agent options.
	 *
	 * @return configured controller implementation
	 */
	IAgentOutput createAgentOutput() {
		final OutputMode controllerType = options.getOutput();
		switch (controllerType) {
		case file:
			return new FileOutput();
		case tcpserver:
			return new TcpServerOutput(logger);
		case tcpclient:
			return new TcpClientOutput(logger);
		case none:
			return new NoneOutput();
		default:
			throw new AssertionError(controllerType);
		}
	}

	private String createSessionId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch (final Exception e) {
			// Also catch platform specific exceptions (like on Android) to
			// avoid bailing out here
			host = "unknownhost";
		}
		return host + "-" + AbstractRuntime.createRandomId();
	}

	// === IAgent Implementation ===

	public String getVersion() {
		return JaCoCo.VERSION;
	}

	public String getSessionId() {
		return data.getSessionId();
	}

	public void setSessionId(final String id) {
		data.setSessionId(id);
	}

	public void reset() {
		data.reset();
	}

	public byte[] getExecutionData(final boolean reset) {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			final ExecutionDataWriter writer = new ExecutionDataWriter(buffer);
			data.collect(writer, writer, reset);
		} catch (final IOException e) {
			// Must not happen with ByteArrayOutputStream
			throw new AssertionError(e);
		}
		return buffer.toByteArray();
	}

	public void dump(final boolean reset) throws IOException {
		output.writeExecutionData(reset);
	}

}
