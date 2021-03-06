/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.openstacknetworking.routing;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.UDP;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.onosproject.openstackinterface.OpenstackInterfaceService;
import org.onosproject.openstackinterface.OpenstackPort;
import org.onosproject.openstackinterface.OpenstackRouter;
import org.onosproject.scalablegateway.api.GatewayNode;
import org.onosproject.scalablegateway.api.ScalableGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onlab.osgi.DefaultServiceDirectory.getService;


/**
 * Handle NAT packet processing for Managing Flow Rules In Openstack Nodes.
 */
public class OpenstackPnatHandler implements Runnable {

    volatile PacketContext context;
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final OpenstackRoutingRulePopulator rulePopulator;
    private final int portNum;
    private final OpenstackPort openstackPort;
    private final Port port;

    private static final String DEVICE_OWNER_ROUTER_INTERFACE = "network:router_interface";
    private static final String EXTERNAL_PORT_NULL = "There is no external port in this deviceId []";

    OpenstackPnatHandler(OpenstackRoutingRulePopulator rulePopulator, PacketContext context,
                         int portNum, OpenstackPort openstackPort, Port port) {
        this.rulePopulator = checkNotNull(rulePopulator);
        this.context = checkNotNull(context);
        this.portNum = checkNotNull(portNum);
        this.openstackPort = checkNotNull(openstackPort);
        this.port = checkNotNull(port);
    }

    @Override
    public void run() {
        InboundPacket inboundPacket = context.inPacket();
        Ethernet ethernet = checkNotNull(inboundPacket.parsed());

        //TODO: Considers IPV6
        if (ethernet.getEtherType() != Ethernet.TYPE_IPV4) {
            log.warn("Now, we just consider IP version 4");
            return;
        }

        OpenstackRouter router = getOpenstackRouter(openstackPort);

        MacAddress externalMac = MacAddress.NONE;
        MacAddress routerMac = MacAddress.NONE;

        rulePopulator.populatePnatFlowRules(inboundPacket, openstackPort, portNum,
                getExternalIp(router), externalMac, routerMac);

        packetOut((Ethernet) ethernet.clone(), inboundPacket.receivedFrom().deviceId(), portNum, router);
    }

    private OpenstackRouter getOpenstackRouter(OpenstackPort openstackPort) {
        OpenstackInterfaceService networkingService = getService(OpenstackInterfaceService.class);

        OpenstackPort port = networkingService.ports()
                .stream()
                .filter(p -> p.deviceOwner().equals(DEVICE_OWNER_ROUTER_INTERFACE))
                .filter(p -> checkSameSubnet(p, openstackPort))
                .findAny()
                .orElse(null);

        return checkNotNull(networkingService.router(port.deviceId()));
    }

    private boolean checkSameSubnet(OpenstackPort p, OpenstackPort openstackPort) {
        String key1 = checkNotNull(p.fixedIps().keySet().stream().findFirst().orElse(null)).toString();
        String key2 = checkNotNull(openstackPort.fixedIps().keySet().stream().findFirst().orElse(null)).toString();
        return key1.equals(key2) ? true : false;
    }

    private Ip4Address getExternalIp(OpenstackRouter router) {
        return router.gatewayExternalInfo().externalFixedIps().values().stream().findAny().orElse(null);
    }

    private void packetOut(Ethernet ethernet, DeviceId deviceId, int portNum, OpenstackRouter router) {
        PacketService packetService = getService(PacketService.class);

        IPv4 iPacket = (IPv4) ethernet.getPayload();

        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();

        switch (iPacket.getProtocol()) {
            case IPv4.PROTOCOL_TCP:
                TCP tcpPacket = (TCP) iPacket.getPayload();
                tcpPacket.setSourcePort(portNum);
                tcpPacket.resetChecksum();
                tcpPacket.setParent(iPacket);
                iPacket.setPayload(tcpPacket);
                break;
            case IPv4.PROTOCOL_UDP:
                UDP udpPacket = (UDP) iPacket.getPayload();
                udpPacket.setSourcePort(portNum);
                udpPacket.resetChecksum();
                udpPacket.setParent(iPacket);
                iPacket.setPayload(udpPacket);
                break;
            default:
                log.error("Temporally, this method can process UDP and TCP protocol.");
                return;
        }

        iPacket.setSourceAddress(getExternalIp(router).toString());
        iPacket.resetChecksum();
        iPacket.setParent(ethernet);
        ethernet.setPayload(iPacket);

        ScalableGatewayService gatewayService = getService(ScalableGatewayService.class);
        GatewayNode gatewayNode = gatewayService.getGatewayNode(deviceId);
        if (gatewayNode.getGatewayExternalInterfaceNames().size() == 0) {
            log.error(EXTERNAL_PORT_NULL, deviceId.toString());
            return;
        }
        treatment.setOutput(gatewayService.getGatewayExternalPorts(deviceId).get(0));

        ethernet.resetChecksum();

        packetService.emit(new DefaultOutboundPacket(deviceId, treatment.build(),
                ByteBuffer.wrap(ethernet.serialize())));
    }
}