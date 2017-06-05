/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.denonmarantz.internal.telnet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.SocketTimeoutException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.net.telnet.TelnetClient;
import org.openhab.binding.denonmarantz.config.DenonMarantzConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manage telnet connection to the Denon/Marantz Receiver
 *
 * @author Jeroen Idserda
 * @author Jan-Willem Veldhuis
 */
public class DenonMarantzTelnetClient extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(DenonMarantzTelnetClient.class);

    private static final Integer RECONNECT_DELAY = 60000; // 1 minute

    private static final Integer TIMEOUT = 60000; // 1 minute

    private DenonMarantzConfiguration config;

    private DenonMarantzTelnetListener listener;

    private TelnetClient tc;

    private boolean running = true;

    public DenonMarantzTelnetClient(DenonMarantzConfiguration config, DenonMarantzTelnetListener listener) {
        logger.debug("Denon listener created");
        this.config = config;
        this.tc = createTelnetClient();
        this.listener = listener;
    }

    @Override
    public void run() {
        while (running) {
            if (!tc.isConnected()) {
                connectTelnetClient();
            }

            InputStream is = tc.getInputStream();
            PrintWriter out = new PrintWriter(tc.getOutputStream(), true);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));

            do {
                try {
                    String line = br.readLine();
                    if (line == null) {
                        logger.debug("No more data read from client. Disconnecting..");
                        listener.telnetClientConnected(false);
                        tc.disconnect();
                        break;
                    }
                    logger.trace("Received from {}: {}", config.getHost(), line);
                    if (!StringUtils.isBlank(line)) {
                        listener.receivedLine(line);
                    }
                } catch (SocketTimeoutException e) {
                    logger.trace("Socket timeout");
                    // Disconnects are not always detected unless you write to the socket.
                    out.print(" ");
                    out.flush();
                } catch (IOException e) {
                    logger.debug("Error in telnet connection ", e);
                    listener.telnetClientConnected(false);
                }
            } while (running && tc.isConnected());
        }
    }

    public void shutdown() {
        this.running = false;
        interrupt();
        disconnect();
    }

    private void connectTelnetClient() {
        disconnect();
        int delay = 0;

        while (!tc.isConnected()) {
            try {
                Thread.sleep(delay);
                logger.debug("TelnetClient object {}", this.toString());
                logger.debug("Connecting to {}", config.getHost());
                tc.connect(config.getHost(), config.getTelnetPort());
                listener.telnetClientConnected(true);
            } catch (IOException e) {
                logger.debug("Cannot connect to {}", config.getHost(), e);
                listener.telnetClientConnected(false);
            } catch (InterruptedException e) {
                logger.debug("Interrupted while connecting to {}", config.getHost(), e);
                listener.telnetClientConnected(false);
                break;
            }
            delay = RECONNECT_DELAY;
        }

        logger.debug("Denon telnet client connected to {}", config.getHost());
    }

    private void disconnect() {
        if (tc != null) {
            try {
                tc.disconnect();
                listener.telnetClientConnected(false);
            } catch (IOException e) {
                logger.debug("Error while disconnecting telnet client", e);
            }
        }
    }

    private TelnetClient createTelnetClient() {
        TelnetClient tc = new TelnetClient();
        tc.setDefaultTimeout(TIMEOUT);
        return tc;
    }

}
