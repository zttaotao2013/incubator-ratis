/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.grpc.client;

import com.google.common.base.Preconditions;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.util.TimeDuration;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.RaftGrpcUtil;
import org.apache.ratis.protocol.*;
import org.apache.ratis.shaded.com.google.protobuf.ByteString;
import org.apache.ratis.shaded.proto.RaftProtos.RaftClientReplyProto;
import org.apache.ratis.shaded.proto.RaftProtos.RaftClientRequestProto;
import org.apache.ratis.shaded.proto.RaftProtos.RaftRpcRequestProto;
import org.apache.ratis.util.Daemon;
import org.apache.ratis.util.PeerProxyMap;
import org.apache.ratis.util.RaftUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.ratis.client.impl.ClientProtoUtils.*;

public class AppendStreamer implements Closeable {
  public static final Logger LOG = LoggerFactory.getLogger(AppendStreamer.class);

  enum RunningState {RUNNING, LOOK_FOR_LEADER, CLOSED, ERROR}

  private static class ExceptionAndRetry {
    private final Map<RaftPeerId, IOException> exceptionMap = new HashMap<>();
    private final AtomicInteger retryTimes = new AtomicInteger(0);
    private final int maxRetryTimes;
    private final TimeDuration retryInterval;

    ExceptionAndRetry(RaftProperties prop) {
      maxRetryTimes = GrpcConfigKeys.OutputStream.retryTimes(prop);
      retryInterval = GrpcConfigKeys.OutputStream.retryInterval(prop);
    }

    void addException(RaftPeerId peer, IOException e) {
      exceptionMap.put(peer, e);
      retryTimes.incrementAndGet();
    }

    IOException getCombinedException() {
      return new IOException("Exceptions: " + exceptionMap);
    }

    boolean shouldRetry() {
      return retryTimes.get() <= maxRetryTimes;
    }
  }

  private final Deque<RaftClientRequestProto> dataQueue;
  private final Deque<RaftClientRequestProto> ackQueue;
  private final int maxPendingNum;

  private final PeerProxyMap<RaftClientProtocolProxy> proxyMap;
  private final Map<RaftPeerId, RaftPeer> peers;
  private RaftPeerId leaderId;
  private volatile RaftClientProtocolProxy leaderProxy;
  private final ClientId clientId;

  private volatile RunningState running = RunningState.RUNNING;
  private final ExceptionAndRetry exceptionAndRetry;
  private final Sender senderThread;

  AppendStreamer(RaftProperties prop, Collection<RaftPeer> peers,
      RaftPeerId leaderId, ClientId clientId) {
    this.clientId = clientId;
    maxPendingNum = GrpcConfigKeys.OutputStream.outstandingAppendsMax(prop);
    dataQueue = new ConcurrentLinkedDeque<>();
    ackQueue = new ConcurrentLinkedDeque<>();
    exceptionAndRetry = new ExceptionAndRetry(prop);

    this.peers = peers.stream().collect(
        Collectors.toMap(RaftPeer::getId, Function.identity()));
    proxyMap = new PeerProxyMap<>(
        raftPeer -> new RaftClientProtocolProxy(raftPeer, ResponseHandler::new));
    proxyMap.addPeers(peers);
    refreshLeaderProxy(leaderId, null);

    senderThread = new Sender();
    senderThread.setName(this.toString() + "-sender");
    senderThread.start();
  }

  private synchronized void refreshLeaderProxy(RaftPeerId suggested,
      RaftPeerId oldLeader) {
    if (suggested != null) {
      leaderId = suggested;
    } else {
      if (oldLeader == null) {
        leaderId = peers.keySet().iterator().next();
      } else {
        leaderId = RaftUtils.next(oldLeader, peers.keySet());
      }
    }
    LOG.debug("{} switches leader from {} to {}. suggested leader: {}", this,
          oldLeader, leaderId, suggested);
    if (leaderProxy != null) {
      leaderProxy.closeCurrentSession();
    }
    try {
      leaderProxy = proxyMap.getProxy(leaderId);
    } catch (IOException e) {
      LOG.error("Should not hit IOException here", e);
      refreshLeader(null, leaderId);
    }
  }

  private boolean isRunning() {
    return running == RunningState.RUNNING ||
        running == RunningState.LOOK_FOR_LEADER;
  }

  private void checkState() throws IOException {
    if (!isRunning()) {
      throwException("The AppendStreamer has been closed");
    }
  }

  synchronized void write(ByteString content, long seqNum)
      throws IOException {
    checkState();
    while (isRunning() && dataQueue.size() >= maxPendingNum) {
      try {
        wait();
      } catch (InterruptedException ignored) {
      }
    }
    if (isRunning()) {
      // wrap the current buffer into a RaftClientRequestProto
      final RaftClientRequestProto request = genRaftClientRequestProto(
          clientId, leaderId, seqNum, content, false);
      dataQueue.offer(request);
      this.notifyAll();
    } else {
      throwException(this + " got closed.");
    }
  }

  synchronized void flush() throws IOException {
    checkState();
    if (dataQueue.isEmpty() && ackQueue.isEmpty()) {
      return;
    }
    // wait for the pending Q to become empty
    while (isRunning() && (!dataQueue.isEmpty() || !ackQueue.isEmpty())) {
      try {
        wait();
      } catch (InterruptedException ignored) {
      }
    }
    if (!isRunning() && (!dataQueue.isEmpty() || !ackQueue.isEmpty())) {
      throwException(this + " got closed before finishing flush");
    }
  }

  @Override
  public void close() throws IOException {
    if (!isRunning()) {
      return;
    }
    flush();

    running = RunningState.CLOSED;
    senderThread.interrupt();
    try {
      senderThread.join();
    } catch (InterruptedException ignored) {
    }
    proxyMap.close();
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + "-" + clientId;
  }

  private class Sender extends Daemon {
    @Override
    public void run() {
      while (isRunning()) {

        synchronized (AppendStreamer.this) {
          while (isRunning() && shouldWait()) {
            try {
              AppendStreamer.this.wait();
            } catch (InterruptedException ignored) {
            }
          }
          if (running == RunningState.RUNNING) {
            RaftClientRequestProto next = dataQueue.poll();
            leaderProxy.onNext(next);
            ackQueue.offer(next);
          }
        }
      }
    }

    private boolean shouldWait() {
      // the sender should wait if any of the following is true
      // 1) there is no data to send
      // 2) there are too many outstanding pending requests
      // 3) Error/NotLeaderException just happened, we're still waiting for
      //    the first response to confirm the new leader
      return dataQueue.isEmpty() || ackQueue.size() >= maxPendingNum ||
          running == RunningState.LOOK_FOR_LEADER;
    }
  }

  /** the response handler for stream RPC */
  private class ResponseHandler implements
      RaftClientProtocolProxy.CloseableStreamObserver {
    private final RaftPeerId targetId;
    // once handled the first NotLeaderException or Error, the handler should
    // be inactive and should not make any further action.
    private volatile boolean active = true;

    ResponseHandler(RaftPeer target) {
      targetId = target.getId();
    }

    @Override
    public String toString() {
      return AppendStreamer.this + "-ResponseHandler-" + targetId;
    }

    @Override
    public void onNext(RaftClientReplyProto reply) {
      if (!active) {
        return;
      }
      synchronized (AppendStreamer.this) {
        RaftClientRequestProto pending = Preconditions.checkNotNull(
            ackQueue.peek());
        if (reply.getRpcReply().getSuccess()) {
          Preconditions.checkState(pending.getRpcRequest().getCallId() ==
              reply.getRpcReply().getCallId());
          ackQueue.poll();
          LOG.trace("{} received success ack for request {}", this,
              pending.getRpcRequest());
          // we've identified the correct leader
          if (running == RunningState.LOOK_FOR_LEADER) {
            running = RunningState.RUNNING;
          }
        } else {
          // this may be a NotLeaderException
          RaftClientReply r = toRaftClientReply(reply);
          if (r.isNotLeader()) {
            LOG.debug("{} received a NotLeaderException from {}", this,
                r.getServerId());
            handleNotLeader(r.getNotLeaderException(), targetId);
          }
        }
        AppendStreamer.this.notifyAll();
      }
    }

    @Override
    public void onError(Throwable t) {
      if (active) {
        synchronized (AppendStreamer.this) {
          handleError(t, this);
          AppendStreamer.this.notifyAll();
        }
      }
    }

    @Override
    public void onCompleted() {
      LOG.info("{} onCompleted, pending requests #: {}", this,
          ackQueue.size());
    }

    @Override // called by handleError and handleNotLeader
    public void close() throws IOException {
      active = false;
    }
  }

  private void throwException(String msg) throws IOException {
    if (running == RunningState.ERROR) {
      throw exceptionAndRetry.getCombinedException();
    } else {
      throw new IOException(msg);
    }
  }

  private void handleNotLeader(NotLeaderException nle,
      RaftPeerId oldLeader) {
    Preconditions.checkState(Thread.holdsLock(AppendStreamer.this));
    // handle NotLeaderException: refresh leader and RaftConfiguration
    refreshPeers(nle.getPeers());

    refreshLeader(nle.getSuggestedLeader().getId(), oldLeader);
  }

  private void handleError(Throwable t, ResponseHandler handler) {
    Preconditions.checkState(Thread.holdsLock(AppendStreamer.this));
    final IOException e = RaftGrpcUtil.unwrapIOException(t);

    exceptionAndRetry.addException(handler.targetId, e);
    LOG.debug("{} got error: {}. Total retry times {}, max retry times {}.",
        handler, e, exceptionAndRetry.retryTimes.get(),
        exceptionAndRetry.maxRetryTimes);

    leaderProxy.onError();
    if (exceptionAndRetry.shouldRetry()) {
      refreshLeader(null, leaderId);
    } else {
      running = RunningState.ERROR;
    }
  }

  private void refreshLeader(RaftPeerId suggestedLeader, RaftPeerId oldLeader) {
    running = RunningState.LOOK_FOR_LEADER;
    refreshLeaderProxy(suggestedLeader, oldLeader);
    reQueuePendingRequests(leaderId);

    final RaftClientRequestProto request = Preconditions.checkNotNull(
        dataQueue.poll());
    ackQueue.offer(request);
    try {
      exceptionAndRetry.retryInterval.sleep();
    } catch (InterruptedException ignored) {
    }
    leaderProxy.onNext(request);
  }

  private void reQueuePendingRequests(RaftPeerId newLeader) {
    if (isRunning()) {
      // resend all the pending requests
      while (!ackQueue.isEmpty()) {
        RaftClientRequestProto oldRequest = ackQueue.pollLast();
        RaftRpcRequestProto r = oldRequest.getRpcRequest();
        RaftClientRequestProto newRequest = RaftClientRequestProto.newBuilder()
            .setMessage(oldRequest.getMessage())
            .setReadOnly(oldRequest.getReadOnly())
            .setRpcRequest(toRaftRpcRequestProtoBuilder(
                clientId.toBytes(), newLeader.toBytes(), r.getCallId()))
            .build();
        dataQueue.offerFirst(newRequest);
      }
    }
  }

  private void refreshPeers(RaftPeer[] newPeers) {
    if (newPeers != null && newPeers.length > 0) {
      // we only add new peers, we do not remove any peer even if it no longer
      // belongs to the current raft conf
      Arrays.stream(newPeers).forEach(peer -> {
        peers.putIfAbsent(peer.getId(), peer);
        proxyMap.putIfAbsent(peer);
      });

      LOG.debug("refreshed peers: {}", peers);
    }
  }
}
