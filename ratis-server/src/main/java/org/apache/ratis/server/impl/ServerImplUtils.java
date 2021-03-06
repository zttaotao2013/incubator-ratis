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
package org.apache.ratis.server.impl;

import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.StateMachine;

import java.io.IOException;

/** Server utilities for internal use. */
public class ServerImplUtils {
  public static RaftServer newRaftServer(
      RaftPeerId id, StateMachine stateMachine, Iterable<RaftPeer> peers,
      RaftProperties properties, Parameters parameters) throws IOException {
    return newRaftServer(id, stateMachine,
        RaftConfiguration.newBuilder().setConf(peers).build(),
        properties, parameters);
  }

  public static RaftServerImpl newRaftServer(
      RaftPeerId id, StateMachine stateMachine, RaftConfiguration conf,
      RaftProperties properties, Parameters parameters) throws IOException {
    return new RaftServerImpl(id, stateMachine, conf, properties, parameters);
  }

  public static TermIndex newTermIndex(long term, long index) {
    return new TermIndexImpl(term, index);
  }

  private static class TermIndexImpl implements TermIndex {
    private final long term;
    private final long index; //log index; first index is 1.

    TermIndexImpl(long term, long logIndex) {
      this.term = term;
      this.index = logIndex;
    }

    @Override
    public long getTerm() {
      return term;
    }

    @Override
    public long getIndex() {
      return index;
    }

    @Override
    public int compareTo(TermIndex that) {
      final int d = Long.compare(this.getTerm(), that.getTerm());
      return d != 0 ? d : Long.compare(this.getIndex(), that.getIndex());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj == null || !(obj instanceof TermIndexImpl)) {
        return false;
      }

      final TermIndexImpl that = (TermIndexImpl) obj;
      return this.getTerm() == that.getTerm()
          && this.getIndex() == that.getIndex();
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder().append(term).append(index).hashCode();
    }

    private static String toString(long n) {
      return n < 0 ? "~" : "" + n;
    }

    @Override
    public String toString() {
      return "(t:" + toString(term) + ", i:" + toString(index) + ")";
    }
  }
}
