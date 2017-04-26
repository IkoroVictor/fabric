/*
Copyright DTCC 2016 All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.hyperledger.fabric.shim;

import java.io.File;

import javax.net.ssl.SSLException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeID;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type;
import org.hyperledger.fabric.protos.peer.ChaincodeSupportGrpc;
import org.hyperledger.fabric.protos.peer.ChaincodeSupportGrpc.ChaincodeSupportStub;
import org.hyperledger.fabric.protos.peer.ProposalResponsePackage.Response;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;

public abstract class ChaincodeBase implements Chaincode {

	/* (non-Javadoc)
	 * @see org.hyperledger.fabric.shim.Chaincode#init(org.hyperledger.fabric.shim.ChaincodeStub)
	 */
	@Override
	public abstract Response init(ChaincodeStub stub);
	
	/* (non-Javadoc)
	 * @see org.hyperledger.fabric.shim.Chaincode#invoke(org.hyperledger.fabric.shim.ChaincodeStub)
	 */
	@Override
	public abstract Response invoke(ChaincodeStub stub);
	
    private static Log logger = LogFactory.getLog(ChaincodeBase.class);
    
    public abstract String getChaincodeID();
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7051;

    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private String hostOverrideAuthority = "";
    private boolean tlsEnabled = false;
    private String rootCertFile = "/etc/hyperledger/fabric/peer.crt";

    private Handler handler;
    private String id = getChaincodeID();

    private final static String CORE_CHAINCODE_ID_NAME = "CORE_CHAINCODE_ID_NAME";
    private final static String CORE_PEER_ADDRESS = "CORE_PEER_ADDRESS";
    private final static String CORE_PEER_TLS_ENABLED = "CORE_PEER_TLS_ENABLED";
    private final static String CORE_PEER_TLS_SERVERHOSTOVERRIDE = "CORE_PEER_TLS_SERVERHOSTOVERRIDE";
    private static final String CORE_PEER_TLS_ROOTCERT_FILE = "CORE_PEER_TLS_ROOTCERT_FILE";

    // Start entry point for chaincodes bootstrap.
    public void start(String[] args) {
	processEnvironmentOptions();
	processCommandLineOptions(args);
	new Thread(() -> {
	    logger.trace("chaincode started");
	    final ManagedChannel connection = newPeerClientConnection();
	    logger.trace("connection created");
	    chatWithPeer(connection);
	    logger.trace("chatWithPeer DONE");
	}).start();
    }

    private void processCommandLineOptions(String[] args) {
	Options options = new Options();
	options.addOption("a", "peerAddress", true, "Address of peer to connect to");
	options.addOption("s", "securityEnabled", false, "Present if security is enabled");
	options.addOption("i", "id", true, "Identity of chaincode");
	options.addOption("o", "hostNameOverride", true, "Hostname override for server certificate");
	try {
	    CommandLine cl = new DefaultParser().parse(options, args);
	    if (cl.hasOption('a')) {
		host = cl.getOptionValue('a');
		port = new Integer(host.split(":")[1]);
		host = host.split(":")[0];
	    }
	    if (cl.hasOption('s')) {
		tlsEnabled = true;
		logger.info("TLS enabled");
		if (cl.hasOption('o')) {
		    hostOverrideAuthority = cl.getOptionValue('o');
		    logger.info("server host override given " + hostOverrideAuthority);
		}
	    }
	    if (cl.hasOption('i')) {
		id = cl.getOptionValue('i');
	    }
	} catch (Exception e) {
	    logger.warn("cli parsing failed with exception", e);

	}
    }

    private void processEnvironmentOptions() {
        if (System.getenv().containsKey(CORE_CHAINCODE_ID_NAME)) {
            this.id = System.getenv(CORE_CHAINCODE_ID_NAME);
        }
        if (System.getenv().containsKey(CORE_PEER_ADDRESS)) {
            this.host = System.getenv(CORE_PEER_ADDRESS);
        }
        if (System.getenv().containsKey(CORE_PEER_TLS_ENABLED)) {
            this.tlsEnabled = Boolean.parseBoolean(System.getenv(CORE_PEER_TLS_ENABLED));
            if (System.getenv().containsKey(CORE_PEER_TLS_SERVERHOSTOVERRIDE)) {
                this.hostOverrideAuthority = System.getenv(CORE_PEER_TLS_SERVERHOSTOVERRIDE);
            }
            if (System.getenv().containsKey(CORE_PEER_TLS_ROOTCERT_FILE)) {
                this.rootCertFile = System.getenv(CORE_PEER_TLS_ROOTCERT_FILE);
            }
        }
    }

    public ManagedChannel newPeerClientConnection() {
	final NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
	logger.info("Configuring channel connection to peer.");

	if (tlsEnabled) {
	    logger.info("TLS is enabled");
	    try {
		final SslContext sslContext = GrpcSslContexts.forClient().trustManager(new File(this.rootCertFile)).build();
		builder.negotiationType(NegotiationType.TLS);
		if (!hostOverrideAuthority.equals("")) {
		    logger.info("Host override " + hostOverrideAuthority);
		    builder.overrideAuthority(hostOverrideAuthority);
		}
		builder.sslContext(sslContext);
		logger.info("TLS context built: " + sslContext);
	    } catch (SSLException e) {
		logger.error("failed connect to peer with SSLException", e);
	    }
	} else {
	    builder.usePlaintext(true);
	}
	return builder.build();
    }

    public void chatWithPeer(ManagedChannel connection) {
	// Establish stream with validating peer
	ChaincodeSupportStub stub = ChaincodeSupportGrpc.newStub(connection);

	logger.info("Connecting to peer.");

	StreamObserver<ChaincodeMessage> requestObserver = null;
	try {
	    requestObserver = stub.register(new StreamObserver<ChaincodeMessage>() {

		@Override
		public void onNext(ChaincodeMessage message) {
		    logger.info("Got message from peer: " + toJsonString(message));
		    try {
			logger.info(String.format("[%s]Received message %s from org.hyperledger.fabric.shim",
				Handler.shortID(message.getTxid()), message.getType()));
			handler.handleMessage(message);
		    } catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		    }
		}

		@Override
		public void onError(Throwable e) {
		    logger.error("Unable to connect to peer server: " + e.getMessage());
		    System.exit(-1);
		}

		@Override
		public void onCompleted() {
		    connection.shutdown();
		    handler.nextState.close();
		}
	    });
	} catch (Exception e) {
	    logger.error("Unable to connect to peer server");
	    System.exit(-1);
	}

	// Create the org.hyperledger.fabric.shim handler responsible for all
	// control logic
	handler = new Handler(requestObserver, this);

	// Send the ChaincodeID during register.
	ChaincodeID chaincodeID = ChaincodeID.newBuilder().setName(id)// TODO
								      // params.get("chaincode.id.name"))
		.build();

	ChaincodeMessage payload = ChaincodeMessage.newBuilder().setPayload(chaincodeID.toByteString())
		.setType(Type.REGISTER).build();

	// Register on the stream
	logger.info(String.format("Registering as '%s' ... sending %s", id, Type.REGISTER));
	handler.serialSend(payload);

	while (true) {
	    try {
		NextStateInfo nsInfo = handler.nextState.take();
		ChaincodeMessage message = nsInfo.message;
		handler.handleMessage(message);
		// keepalive messages are PONGs to the fabric's PINGs
		if (nsInfo.sendToCC || message.getType() == Type.KEEPALIVE) {
		    if (message.getType() == Type.KEEPALIVE) {
			logger.info("Sending KEEPALIVE response");
		    } else {
			logger.info(
				"[" + Handler.shortID(message.getTxid()) + "]Send state message " + message.getType());
		    }
		    handler.serialSend(message);
		}
	    } catch (Exception e) {
		break;
	    }
	}
    }

    static String toJsonString(ChaincodeMessage message) {
        try {
    	return JsonFormat.printer().print(message);
        } catch(InvalidProtocolBufferException e) {
    	return String.format("{ Type: %s, TxId: %s }", message.getType(), message.getTxid());
        }
    }
}
