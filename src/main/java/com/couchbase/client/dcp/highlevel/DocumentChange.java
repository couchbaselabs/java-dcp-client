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

package com.couchbase.client.dcp.highlevel;

import com.couchbase.client.dcp.core.logging.RedactableArgument;
import com.couchbase.client.dcp.highlevel.internal.DatabaseChangeEvent;
import com.couchbase.client.dcp.highlevel.internal.FlowControlReceipt;
import com.couchbase.client.dcp.highlevel.internal.FlowControllable;
import com.couchbase.client.dcp.message.DcpMutationMessage;
import com.couchbase.client.dcp.message.MessageUtil;
import io.netty.buffer.ByteBuf;

import static java.util.Objects.requireNonNull;

public abstract class DocumentChange implements DatabaseChangeEvent, FlowControllable {
  private final int vbucket;
  private final StreamOffset offset;
  private final String key;
  private final byte[] content;
  private final boolean mutation;
  private final long revision;
  private final long cas;

  private final FlowControlReceipt receipt;

  public DocumentChange(ByteBuf byteBuf, FlowControlReceipt receipt, long vbucketUuid, SnapshotMarker snapshot) {
    this.vbucket = MessageUtil.getVbucket(byteBuf);
    this.key = MessageUtil.getKeyAsString(byteBuf);
    this.mutation = DcpMutationMessage.is(byteBuf);

    final long seqno = DcpMutationMessage.bySeqno(byteBuf); // same method works for deletion and expiration, too
    this.revision = DcpMutationMessage.revisionSeqno(byteBuf); // same method works for deletion and expiration, too

    this.offset = new StreamOffset(vbucketUuid, seqno, snapshot);
    this.receipt = requireNonNull(receipt);
    this.content = MessageUtil.getContentAsByteArray(byteBuf);
    this.cas = MessageUtil.getCas(byteBuf);
  }

  public byte[] getContent() {
    return content;
  }

  public int getVbucket() {
    return vbucket;
  }

  public StreamOffset getOffset() {
    return offset;
  }

  public String getKey() {
    return key;
  }

  public boolean isMutation() {
    return mutation;
  }

  public long getRevision() {
    return revision;
  }

  public long getCas() {
    return cas;
  }

  /**
   * Removes the backpressure generated by this event, allowing the server
   * to send more data.
   * <p>
   * If flow control is enabled on the client, then non-blocking listeners
   * and listeners using {@link FlowControlMode#MANUAL} <b>MUST</b> call
   * this method when the application is ready to receive more data
   * (usually when the app has finished processing the event),
   * otherwise the server will stop sending events.
   * <p>
   * This method is idempotent; if it is called more than once, any
   * calls after the first are ignored.
   */
  @Override
  public void flowControlAck() {
    receipt.acknowledge();
  }

  @Override
  public String toString() {
    final String type = isMutation() ? "MUT" : "DEL";
    return type + ":" + getVbucket() + "/" + getOffset() + "=" + RedactableArgument.user(getKey());
  }
}
