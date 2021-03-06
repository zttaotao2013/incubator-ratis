/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.ratis.rpc;

import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;

/** The type of RPC implementations. */
public interface RpcType {
  /** @return the name of the rpc type. */
  String name();

  /** @return a new factory created using the given properties and parameters. */
  RpcFactory newFactory(RaftProperties properties, Parameters parameters);

  /** An interface to get {@link RpcType}. */
  interface Get {
    /** @return the {@link RpcType}. */
    RpcType getRpcType();
  }
}