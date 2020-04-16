/*
 * Copyright 2020 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.dcp.test.agent;

import com.github.therapi.core.annotation.Remotable;

@Remotable("cluster")
public interface ClusterService {
  /**
   * Resets the Cluster used by the other services.
   * <p>
   * Prevents the Java SDK from trying to reconnect to buckets that have been deleted.
   */
  void reset();
}
