/*
 * Copyright 2013 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.esciencecenter.amuse.distributed.workers;

import ibis.ipl.ConnectionClosedException;
import ibis.ipl.Ibis;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.ReceivePortIdentifier;
import ibis.ipl.ReceiveTimedOutException;
import ibis.ipl.SendPort;
import ibis.ipl.WriteMessage;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.UUID;

import nl.esciencecenter.amuse.distributed.AmuseMessage;
import nl.esciencecenter.amuse.distributed.DistributedAmuse;
import nl.esciencecenter.amuse.distributed.jobs.AmuseJob;
import nl.esciencecenter.amuse.distributed.jobs.JobSet;
import nl.esciencecenter.amuse.distributed.jobs.WorkerJobDescription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class responsible for taking method invocation messages from AMUSE, and forwarding them to a remote worker proxy.
 * 
 * @author Niels Drost
 * 
 */
public class WorkerConnection extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(WorkerConnection.class);

    private static final Logger PROFILE_LOGGER = LoggerFactory.getLogger("amuse.profile");

    public static final int CONNECT_TIMEOUT = 60000;

    private final SocketChannel socket;

    private final JobSet jobManager;

    private final String id;

    private final ReceivePort receivePort;

    private final SendPort sendPort;

    private final AmuseMessage initRequest;

    private final WorkerJobDescription workerDescription;

    private final AmuseJob job;

    /*
     * Initializes worker by reading settings from amuse, deploying the worker
     * process on a (possibly remote) machine, and waiting for a connection from
     * the worker
     */
    WorkerConnection(SocketChannel socket, Ibis ibis, JobSet jobManager) throws Exception {
        this.socket = socket;
        this.jobManager = jobManager;

        this.id = UUID.randomUUID().toString();

        if (logger.isDebugEnabled()) {
            logger.debug("New worker connection from " + socket.socket().getRemoteSocketAddress());
        }

        // read initialization call

        initRequest = new AmuseMessage();
        initRequest.readFrom(socket);

        if (initRequest.getFunctionID() != AmuseMessage.FUNCTION_ID_INIT) {
            throw new IOException("first call to worker must be init function");
        }

        //description of the worker, used for both the scheduler and the code proxy to start the worker properly
        workerDescription = new WorkerJobDescription(initRequest, id);

        // initialize ibis ports
        receivePort = ibis.createReceivePort(DistributedAmuse.ONE_TO_ONE_PORT_TYPE, id);
        receivePort.enableConnections();

        sendPort = ibis.createSendPort(DistributedAmuse.ONE_TO_ONE_PORT_TYPE);

        // start deployment of worker (possibly on remote machine)
        job = jobManager.submitWorkerJob(workerDescription);

        logger.info("New worker submitted : {} with description : {}", this, workerDescription);

        setDaemon(true);
        start();
    }

    void logProfileDescription() {
        PROFILE_LOGGER
                .trace("worker-id worker-executable function-id call-id request-size(bytes) reply-size(bytes) start(epoch) stop(epoch) time(ms) time-at-remote(ms)");
    }

    void logProfile(AmuseMessage request, AmuseMessage result, long start, long stop, long remoteExecutionTime) {
        long time = stop - start;
        PROFILE_LOGGER.trace("{} {} {} {} {} {} {} {} {} {}", workerDescription.getID(), workerDescription.getExecutable(),
                request.getFunctionID(), request.getCallID(), request.getDataSize(), result.getDataSize(), start, stop, time,
                remoteExecutionTime);
    }

    public void run() {
        AmuseMessage request = new AmuseMessage();
        AmuseMessage result = new AmuseMessage();
        long start, finish;

        // finish initializing worker
        try {

            // Wait until job is running. Will not wait for more than time requested
            job.waitUntilRunning(workerDescription.getStartupTimeout() * 1000);

            if (!job.isRunning()) {
                throw new Exception("Worker not started within set time (" + workerDescription.getStartupTimeout()
                        + " seconds). Current state: " + job.getJobState());
            }

            //read initial "hello" message with identifier
            ReadMessage helloMessage = receivePort.receive(CONNECT_TIMEOUT);

            ReceivePortIdentifier remotePort = (ReceivePortIdentifier) helloMessage.readObject();

            String amuseHome = (String) helloMessage.readObject();

            helloMessage.finish();

            sendPort.connect(remotePort, CONNECT_TIMEOUT, true);

            //            // do init function at remote worker so it can initialize the code
            //
            //            // write init message
            //            WriteMessage initWriteMessage = sendPort.newMessage();
            //            initRequest.writeTo(initWriteMessage);
            //            initWriteMessage.finish();
            //
            //            // read reply
            //            AmuseMessage initReply = new AmuseMessage();
            //            ReadMessage initReadMessage = receivePort.receive();
            //            initReply.readFrom(initReadMessage);
            //            initReadMessage.finish();
            //
            //            if (initReply.getError() != null) {
            //                throw new IOException(initReply.getError());
            //            }
            //

            //send a reply
            AmuseMessage initReply = new AmuseMessage(initRequest.getCallID(), initRequest.getFunctionID(),
                    initRequest.getCallCount());
            initReply.addString(amuseHome);
            initReply.writeTo(socket);

        } catch (Exception e) {
            if (socket.isOpen() && socket.isConnected()) {
                logger.error("Error on handling call", e);

                // report error to amuse
                AmuseMessage errormessage = new AmuseMessage(initRequest.getCallID(), initRequest.getFunctionID(),
                        initRequest.getCallCount(), "Amuse error: " + e.getMessage());
                try {
                    errormessage.writeTo(socket);
                } catch (IOException e1) {
                    logger.error("Error while returning error message to amuse", e1);
                }
            } else {
                logger.error("Error on handling call, lost connection to AMUSE", e);
            }
            end();
            return;
        }

        logger.info("New worker successfully started: " + this);

        if (PROFILE_LOGGER.isTraceEnabled()) {
            logProfileDescription();
        }

        boolean running = true;

        while (running && socket.isOpen() && socket.isConnected()) {

            try {
                // logger.debug("wating for request...");
                request.readFrom(socket);
                
                start = System.currentTimeMillis();

                // logger.debug("performing request " + request);

                if (request.getFunctionID() == AmuseMessage.FUNCTION_ID_STOP) {
                    // this will be the last call we perform
                    running = false;
                }

                if (job.isDone()) {
                    throw new IOException("Remote Code Proxy no longer running");
                }

                WriteMessage writeMessage = sendPort.newMessage();
                request.writeTo(writeMessage);
                writeMessage.finish();

                logger.trace("waiting for result");

                ReadMessage readMessage = null;

                while (readMessage == null) {
                    try {
                        readMessage = receivePort.receive(1000);
                    } catch (ReceiveTimedOutException exception) {
                        // IGNORE
                    }

                    if (receivePort.connectedTo().length == 0 || job.isDone()) {
                        throw new IOException("receiveport no longer connected to remote proxy, or proxy no longer running");
                    }
                }
                result.readFrom(readMessage);
                long remoteExecutionTime = readMessage.readLong();
                readMessage.finish();

                if (result.isErrorState()) {
                    logger.warn("Error while doing call at worker", result.getError());
                }

                if (logger.isTraceEnabled()) {
                    logger.trace("request " + request.getCallID() + " handled, result: " + result);
                }

                finish = System.currentTimeMillis();

                // forward result to the channel
                result.writeTo(socket);

                if (logger.isTraceEnabled()) {
                    logger.trace("call took " + (finish - start) + " ms");
                }

                if (PROFILE_LOGGER.isTraceEnabled()) {
                    logProfile(request, result, start, finish, remoteExecutionTime);
                }
            } catch (ConnectionClosedException e) {
                logger.info("channel closed on receiving request");
                running = false;
            } catch (IOException e) {
                running = false;
                if (socket.isOpen() && socket.isConnected()) {
                    logger.error("Error on handling call", e);

                    // report error to amuse
                    AmuseMessage errormessage = new AmuseMessage(request.getCallID(), request.getFunctionID(),
                            request.getCallCount(), "Ibis/Amuse error: " + e.getMessage());
                    try {
                        errormessage.writeTo(socket);
                    } catch (IOException e1) {
                        logger.error("Error while returning error message to amuse", e1);
                    }

                } else {
                    logger.error("Error on handling call, lost connection to AMUSE", e);
                }
            }
        }
        logger.debug(this + " ending");
        end();
        logger.info("Worker {} ended", id);
    }

    private void end() {
        try {
            sendPort.close();
        } catch (IOException e) {
            logger.error("Error closing sendport", e);
        }

        try {
            receivePort.close(1000);
        } catch (IOException e) {
            logger.error("Error closing receiveport", e);
        }
        try {
            job.cancel();
        } catch (Exception e2) {
            logger.error("Error cancelling job", e2);
        }
    }

    @Override
    public String toString() {
        return "WorkerConnection [id=" + id + ", executable=" + workerDescription.getExecutable() + ", nodeLabel="
                + workerDescription.getNodeLabel() + "]";
    }

}
