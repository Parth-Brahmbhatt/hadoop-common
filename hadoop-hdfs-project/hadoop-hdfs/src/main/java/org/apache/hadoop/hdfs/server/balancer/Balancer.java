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
package org.apache.hadoop.hdfs.server.balancer;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.hadoop.fs.CommonConfigurationKeys.IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_DEFAULT;
import static org.apache.hadoop.fs.CommonConfigurationKeys.IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_KEY;
import static org.apache.hadoop.hdfs.protocolPB.PBHelper.vintPrefixed;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.StorageType;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.hdfs.protocol.datatransfer.Sender;
import org.apache.hadoop.hdfs.protocol.datatransfer.TrustedChannelResolver;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataTransferSaslUtil;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.SaslDataTransferClient;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.BlockOpResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.Status;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicy;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicyDefault;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.namenode.UnsupportedActionException;
import org.apache.hadoop.hdfs.server.protocol.BlocksWithLocations.BlockWithLocations;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorageReport;
import org.apache.hadoop.hdfs.server.protocol.StorageReport;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.HostsFileReader;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.google.common.base.Preconditions;

/** <p>The balancer is a tool that balances disk space usage on an HDFS cluster
 * when some datanodes become full or when new empty nodes join the cluster.
 * The tool is deployed as an application program that can be run by the 
 * cluster administrator on a live HDFS cluster while applications
 * adding and deleting files.
 * 
 * <p>SYNOPSIS
 * <pre>
 * To start:
 *      bin/start-balancer.sh [-threshold <threshold>]
 *      Example: bin/ start-balancer.sh 
 *                     start the balancer with a default threshold of 10%
 *               bin/ start-balancer.sh -threshold 5
 *                     start the balancer with a threshold of 5%
 * To stop:
 *      bin/ stop-balancer.sh
 * </pre>
 * 
 * <p>DESCRIPTION
 * <p>The threshold parameter is a fraction in the range of (1%, 100%) with a 
 * default value of 10%. The threshold sets a target for whether the cluster 
 * is balanced. A cluster is balanced if for each datanode, the utilization 
 * of the node (ratio of used space at the node to total capacity of the node) 
 * differs from the utilization of the (ratio of used space in the cluster 
 * to total capacity of the cluster) by no more than the threshold value. 
 * The smaller the threshold, the more balanced a cluster will become. 
 * It takes more time to run the balancer for small threshold values. 
 * Also for a very small threshold the cluster may not be able to reach the 
 * balanced state when applications write and delete files concurrently.
 * 
 * <p>The tool moves blocks from highly utilized datanodes to poorly 
 * utilized datanodes iteratively. In each iteration a datanode moves or 
 * receives no more than the lesser of 10G bytes or the threshold fraction 
 * of its capacity. Each iteration runs no more than 20 minutes.
 * At the end of each iteration, the balancer obtains updated datanodes
 * information from the namenode.
 * 
 * <p>A system property that limits the balancer's use of bandwidth is 
 * defined in the default configuration file:
 * <pre>
 * <property>
 *   <name>dfs.balance.bandwidthPerSec</name>
 *   <value>1048576</value>
 * <description>  Specifies the maximum bandwidth that each datanode 
 * can utilize for the balancing purpose in term of the number of bytes 
 * per second. </description>
 * </property>
 * </pre>
 * 
 * <p>This property determines the maximum speed at which a block will be 
 * moved from one datanode to another. The default value is 1MB/s. The higher 
 * the bandwidth, the faster a cluster can reach the balanced state, 
 * but with greater competition with application processes. If an 
 * administrator changes the value of this property in the configuration 
 * file, the change is observed when HDFS is next restarted.
 * 
 * <p>MONITERING BALANCER PROGRESS
 * <p>After the balancer is started, an output file name where the balancer 
 * progress will be recorded is printed on the screen.  The administrator 
 * can monitor the running of the balancer by reading the output file. 
 * The output shows the balancer's status iteration by iteration. In each 
 * iteration it prints the starting time, the iteration number, the total 
 * number of bytes that have been moved in the previous iterations, 
 * the total number of bytes that are left to move in order for the cluster 
 * to be balanced, and the number of bytes that are being moved in this 
 * iteration. Normally "Bytes Already Moved" is increasing while "Bytes Left 
 * To Move" is decreasing.
 * 
 * <p>Running multiple instances of the balancer in an HDFS cluster is 
 * prohibited by the tool.
 * 
 * <p>The balancer automatically exits when any of the following five 
 * conditions is satisfied:
 * <ol>
 * <li>The cluster is balanced;
 * <li>No block can be moved;
 * <li>No block has been moved for five consecutive iterations;
 * <li>An IOException occurs while communicating with the namenode;
 * <li>Another balancer is running.
 * </ol>
 * 
 * <p>Upon exit, a balancer returns an exit code and prints one of the 
 * following messages to the output file in corresponding to the above exit 
 * reasons:
 * <ol>
 * <li>The cluster is balanced. Exiting
 * <li>No block can be moved. Exiting...
 * <li>No block has been moved for 5 iterations. Exiting...
 * <li>Received an IO exception: failure reason. Exiting...
 * <li>Another balancer is running. Exiting...
 * </ol>
 * 
 * <p>The administrator can interrupt the execution of the balancer at any 
 * time by running the command "stop-balancer.sh" on the machine where the 
 * balancer is running.
 */

@InterfaceAudience.Private
public class Balancer {
  static final Log LOG = LogFactory.getLog(Balancer.class);

  private static final Path BALANCER_ID_PATH = new Path("/system/balancer.id");

  private static final long GB = 1L << 30; //1GB
  private static final long MAX_SIZE_TO_MOVE = 10*GB;
  private static final long MAX_BLOCKS_SIZE_TO_FETCH = 2*GB;

  /** The maximum number of concurrent blocks moves for 
   * balancing purpose at a datanode
   */
  private static final int MAX_NO_PENDING_BLOCK_ITERATIONS = 5;
  public static final long DELAY_AFTER_ERROR = 10 * 1000L; //10 seconds
  public static final int BLOCK_MOVE_READ_TIMEOUT=20*60*1000; // 20 minutes
  
  private static final String USAGE = "Usage: java "
      + Balancer.class.getSimpleName()
      + "\n\t[-policy <policy>]\tthe balancing policy: "
      + BalancingPolicy.Node.INSTANCE.getName() + " or "
      + BalancingPolicy.Pool.INSTANCE.getName()
      + "\n\t[-threshold <threshold>]\tPercentage of disk capacity"
      + "\n\t[-exclude [-f <hosts-file> | comma-sperated list of hosts]]"
      + "\tExcludes the specified datanodes."
      + "\n\t[-include [-f <hosts-file> | comma-sperated list of hosts]]"
      + "\tIncludes only the specified datanodes.";
  
  private final NameNodeConnector nnc;
  private final KeyManager keyManager;

  private final BalancingPolicy policy;
  private final SaslDataTransferClient saslClient;
  private final double threshold;
  // set of data nodes to be excluded from balancing operations.
  Set<String> nodesToBeExcluded;
  //Restrict balancing to the following nodes.
  Set<String> nodesToBeIncluded;
  
  // all data node lists
  private final Collection<Source> overUtilized = new LinkedList<Source>();
  private final Collection<Source> aboveAvgUtilized = new LinkedList<Source>();
  private final Collection<BalancerDatanode.StorageGroup> belowAvgUtilized
      = new LinkedList<BalancerDatanode.StorageGroup>();
  private final Collection<BalancerDatanode.StorageGroup> underUtilized
      = new LinkedList<BalancerDatanode.StorageGroup>();
  
  private final Collection<Source> sources = new HashSet<Source>();
  private final Collection<BalancerDatanode.StorageGroup> targets
      = new HashSet<BalancerDatanode.StorageGroup>();
  
  private final Map<Block, BalancerBlock> globalBlockList
                 = new HashMap<Block, BalancerBlock>();
  private final MovedBlocks<BalancerDatanode.StorageGroup> movedBlocks;

  /** Map (datanodeUuid,storageType -> StorageGroup) */
  private final StorageGroupMap storageGroupMap = new StorageGroupMap();
  
  private NetworkTopology cluster;

  private final ExecutorService moverExecutor;
  private final ExecutorService dispatcherExecutor;
  private final int maxConcurrentMovesPerNode;


  private static class StorageGroupMap {
    private static String toKey(String datanodeUuid, StorageType storageType) {
      return datanodeUuid + ":" + storageType;
    }

    private final Map<String, BalancerDatanode.StorageGroup> map
        = new HashMap<String, BalancerDatanode.StorageGroup>();
    
    BalancerDatanode.StorageGroup get(String datanodeUuid, StorageType storageType) {
      return map.get(toKey(datanodeUuid, storageType));
    }
    
    void put(BalancerDatanode.StorageGroup g) {
      final String key = toKey(g.getDatanode().getDatanodeUuid(), g.storageType);
      final BalancerDatanode.StorageGroup existing = map.put(key, g);
      Preconditions.checkState(existing == null);
    }
    
    int size() {
      return map.size();
    }
    
    void clear() {
      map.clear();
    }
  }
  /* This class keeps track of a scheduled block move */
  private class PendingBlockMove {
    private BalancerBlock block;
    private Source source;
    private BalancerDatanode proxySource;
    private BalancerDatanode.StorageGroup target;
    
    /** constructor */
    private PendingBlockMove() {
    }
    
    @Override
    public String toString() {
      final Block b = block.getBlock();
      return b + " with size=" + b.getNumBytes() + " from "
          + source.getDisplayName() + " to " + target.getDisplayName()
          + " through " + proxySource.datanode;
    }

    /* choose a block & a proxy source for this pendingMove 
     * whose source & target have already been chosen.
     * 
     * Return true if a block and its proxy are chosen; false otherwise
     */
    private boolean chooseBlockAndProxy() {
      // iterate all source's blocks until find a good one
      for (Iterator<BalancerBlock> blocks=
        source.getBlockIterator(); blocks.hasNext();) {
        if (markMovedIfGoodBlock(blocks.next())) {
          blocks.remove();
          return true;
        }
      }
      return false;
    }
    
    /* Return true if the given block is good for the tentative move;
     * If it is good, add it to the moved list to marked as "Moved".
     * A block is good if
     * 1. it is a good candidate; see isGoodBlockCandidate
     * 2. can find a proxy source that's not busy for this move
     */
    private boolean markMovedIfGoodBlock(BalancerBlock block) {
      synchronized(block) {
        synchronized(movedBlocks) {
          if (isGoodBlockCandidate(source, target, block)) {
            this.block = block;
            if ( chooseProxySource() ) {
              movedBlocks.put(block);
              if (LOG.isDebugEnabled()) {
                LOG.debug("Decided to move " + this);
              }
              return true;
            }
          }
        }
      }
      return false;
    }
    
    /* Now we find out source, target, and block, we need to find a proxy
     * 
     * @return true if a proxy is found; otherwise false
     */
    private boolean chooseProxySource() {
      final DatanodeInfo targetDN = target.getDatanode();
      // if node group is supported, first try add nodes in the same node group
      if (cluster.isNodeGroupAware()) {
        for (BalancerDatanode.StorageGroup loc : block.getLocations()) {
          if (cluster.isOnSameNodeGroup(loc.getDatanode(), targetDN) && addTo(loc)) {
            return true;
          }
        }
      }
      // check if there is replica which is on the same rack with the target
      for (BalancerDatanode.StorageGroup loc : block.getLocations()) {
        if (cluster.isOnSameRack(loc.getDatanode(), targetDN) && addTo(loc)) {
          return true;
        }
      }
      // find out a non-busy replica
      for (BalancerDatanode.StorageGroup loc : block.getLocations()) {
        if (addTo(loc)) {
          return true;
        }
      }
      return false;
    }
    
    /** add to a proxy source for specific block movement */
    private boolean addTo(BalancerDatanode.StorageGroup g) {
      final BalancerDatanode bdn = g.getBalancerDatanode();
      if (bdn.addPendingBlock(this)) {
        proxySource = bdn;
        return true;
      }
      return false;
    }
    
    /* Dispatch the block move task to the proxy source & wait for the response
     */
    private void dispatch() {
      Socket sock = new Socket();
      DataOutputStream out = null;
      DataInputStream in = null;
      try {
        sock.connect(
            NetUtils.createSocketAddr(target.getDatanode().getXferAddr()),
            HdfsServerConstants.READ_TIMEOUT);
        /* Unfortunately we don't have a good way to know if the Datanode is
         * taking a really long time to move a block, OR something has
         * gone wrong and it's never going to finish. To deal with this 
         * scenario, we set a long timeout (20 minutes) to avoid hanging
         * the balancer indefinitely.
         */
        sock.setSoTimeout(BLOCK_MOVE_READ_TIMEOUT);

        sock.setKeepAlive(true);
        
        OutputStream unbufOut = sock.getOutputStream();
        InputStream unbufIn = sock.getInputStream();
        ExtendedBlock eb = new ExtendedBlock(nnc.getBlockpoolID(), block.getBlock());
        Token<BlockTokenIdentifier> accessToken = keyManager.getAccessToken(eb);
        IOStreamPair saslStreams = saslClient.socketSend(sock, unbufOut,
          unbufIn, keyManager, accessToken, target.getDatanode());
        unbufOut = saslStreams.out;
        unbufIn = saslStreams.in;
        out = new DataOutputStream(new BufferedOutputStream(unbufOut,
            HdfsConstants.IO_FILE_BUFFER_SIZE));
        in = new DataInputStream(new BufferedInputStream(unbufIn,
            HdfsConstants.IO_FILE_BUFFER_SIZE));
        
        sendRequest(out, eb, StorageType.DEFAULT, accessToken);
        receiveResponse(in);
        bytesMoved.addAndGet(block.getNumBytes());
        LOG.info("Successfully moved " + this);
      } catch (IOException e) {
        LOG.warn("Failed to move " + this + ": " + e.getMessage());
        /* proxy or target may have an issue, insert a small delay
         * before using these nodes further. This avoids a potential storm
         * of "threads quota exceeded" Warnings when the balancer
         * gets out of sync with work going on in datanode.
         */
        proxySource.activateDelay(DELAY_AFTER_ERROR);
        target.getBalancerDatanode().activateDelay(DELAY_AFTER_ERROR);
      } finally {
        IOUtils.closeStream(out);
        IOUtils.closeStream(in);
        IOUtils.closeSocket(sock);
        
        proxySource.removePendingBlock(this);
        target.getBalancerDatanode().removePendingBlock(this);

        synchronized (this ) {
          reset();
        }
        synchronized (Balancer.this) {
          Balancer.this.notifyAll();
        }
      }
    }
    
    /* Send a block replace request to the output stream*/
    private void sendRequest(DataOutputStream out, ExtendedBlock eb,
        StorageType storageType, 
        Token<BlockTokenIdentifier> accessToken) throws IOException {
      new Sender(out).replaceBlock(eb, storageType, accessToken,
          source.getDatanode().getDatanodeUuid(), proxySource.datanode);
    }
    
    /* Receive a block copy response from the input stream */ 
    private void receiveResponse(DataInputStream in) throws IOException {
      BlockOpResponseProto response = BlockOpResponseProto.parseFrom(
          vintPrefixed(in));
      if (response.getStatus() != Status.SUCCESS) {
        if (response.getStatus() == Status.ERROR_ACCESS_TOKEN)
          throw new IOException("block move failed due to access token error");
        throw new IOException("block move is failed: " +
            response.getMessage());
      }
    }

    /* reset the object */
    private void reset() {
      block = null;
      source = null;
      proxySource = null;
      target = null;
    }
    
    /* start a thread to dispatch the block move */
    private void scheduleBlockMove() {
      moverExecutor.execute(new Runnable() {
        @Override
        public void run() {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Start moving " + PendingBlockMove.this);
          }
          dispatch();
        }
      });
    }
  }
  
  /* A class for keeping track of blocks in the Balancer */
  static class BalancerBlock extends MovedBlocks.Locations<BalancerDatanode.StorageGroup> {
    BalancerBlock(Block block) {
      super(block);
    }
  }
  
  /* The class represents a desired move of bytes between two nodes 
   * and the target.
   * An object of this class is stored in a source. 
   */
  static private class Task {
    private final BalancerDatanode.StorageGroup target;
    private long size;  //bytes scheduled to move
    
    /* constructor */
    private Task(BalancerDatanode.StorageGroup target, long size) {
      this.target = target;
      this.size = size;
    }
  }
  
  
  /* A class that keeps track of a datanode in Balancer */
  private static class BalancerDatanode {
    
    /** A group of storages in a datanode with the same storage type. */
    private class StorageGroup {
      final StorageType storageType;
      final double utilization;
      final long maxSize2Move;
      private long scheduledSize = 0L;

      private StorageGroup(StorageType storageType, double utilization,
          long maxSize2Move) {
        this.storageType = storageType;
        this.utilization = utilization;
        this.maxSize2Move = maxSize2Move;
      }
      
      BalancerDatanode getBalancerDatanode() {
        return BalancerDatanode.this;
      }

      DatanodeInfo getDatanode() {
        return BalancerDatanode.this.datanode;
      }

      /** Decide if still need to move more bytes */
      protected synchronized boolean hasSpaceForScheduling() {
        return availableSizeToMove() > 0L;
      }

      /** @return the total number of bytes that need to be moved */
      synchronized long availableSizeToMove() {
        return maxSize2Move - scheduledSize;
      }
      
      /** increment scheduled size */
      synchronized void incScheduledSize(long size) {
        scheduledSize += size;
      }
      
      /** @return scheduled size */
      synchronized long getScheduledSize() {
        return scheduledSize;
      }
      
      /** Reset scheduled size to zero. */
      synchronized void resetScheduledSize() {
        scheduledSize = 0L;
      }

      /** @return the name for display */
      String getDisplayName() {
        return datanode + ":" + storageType;
      }
      
      @Override
      public String toString() {
        return "" + utilization;
      }
    }

    final DatanodeInfo datanode;
    final EnumMap<StorageType, StorageGroup> storageMap
        = new EnumMap<StorageType, StorageGroup>(StorageType.class);
    protected long delayUntil = 0L;
    //  blocks being moved but not confirmed yet
    private final List<PendingBlockMove> pendingBlocks;
    private final int maxConcurrentMoves;
    
    @Override
    public String toString() {
      return getClass().getSimpleName() + ":" + datanode + ":" + storageMap;
    }

    /* Constructor 
     * Depending on avgutil & threshold, calculate maximum bytes to move 
     */
    private BalancerDatanode(DatanodeStorageReport report,
        double threshold, int maxConcurrentMoves) {
      this.datanode = report.getDatanodeInfo();
      this.maxConcurrentMoves = maxConcurrentMoves;
      this.pendingBlocks = new ArrayList<PendingBlockMove>(maxConcurrentMoves);
    }
    
    private void put(StorageType storageType, StorageGroup g) {
      final StorageGroup existing = storageMap.put(storageType, g);
      Preconditions.checkState(existing == null);
    }

    StorageGroup addStorageGroup(StorageType storageType, double utilization,
        long maxSize2Move) {
      final StorageGroup g = new StorageGroup(storageType, utilization,
          maxSize2Move);
      put(storageType, g);
      return g;
    }

    Source addSource(StorageType storageType, double utilization,
        long maxSize2Move, Balancer balancer) {
      final Source s = balancer.new Source(storageType, utilization,
          maxSize2Move, this);
      put(storageType, s);
      return s;
    }

    synchronized private void activateDelay(long delta) {
      delayUntil = Time.now() + delta;
    }

    synchronized private boolean isDelayActive() {
      if (delayUntil == 0 || Time.now() > delayUntil){
        delayUntil = 0;
        return false;
      }
        return true;
    }
    
    /* Check if the node can schedule more blocks to move */
    synchronized private boolean isPendingQNotFull() {
      if ( pendingBlocks.size() < this.maxConcurrentMoves ) {
        return true;
      }
      return false;
    }
    
    /* Check if all the dispatched moves are done */
    synchronized private boolean isPendingQEmpty() {
      return pendingBlocks.isEmpty();
    }
    
    /* Add a scheduled block move to the node */
    private synchronized boolean addPendingBlock(
        PendingBlockMove pendingBlock) {
      if (!isDelayActive() && isPendingQNotFull()) {
        return pendingBlocks.add(pendingBlock);
      }
      return false;
    }
    
    /* Remove a scheduled block move from the node */
    private synchronized boolean  removePendingBlock(
        PendingBlockMove pendingBlock) {
      return pendingBlocks.remove(pendingBlock);
    }
  }

  /** A node that can be the sources of a block move */
  private class Source extends BalancerDatanode.StorageGroup {
    
    /* A thread that initiates a block move 
     * and waits for block move to complete */
    private class BlockMoveDispatcher implements Runnable {
      @Override
      public void run() {
        dispatchBlocks();
      }
    }
    
    private final List<Task> tasks = new ArrayList<Task>(2);
    private long blocksToReceive = 0L;
    /* source blocks point to balancerBlocks in the global list because
     * we want to keep one copy of a block in balancer and be aware that
     * the locations are changing over time.
     */
    private final List<BalancerBlock> srcBlockList
            = new ArrayList<BalancerBlock>();
    
    /* constructor */
    private Source(StorageType storageType, double utilization,
        long maxSize2Move, BalancerDatanode dn) {
      dn.super(storageType, utilization, maxSize2Move);
    }
    
    /** Add a task */
    private void addTask(Task task) {
      Preconditions.checkState(task.target != this,
          "Source and target are the same storage group " + getDisplayName());
      incScheduledSize(task.size);
      tasks.add(task);
    }
    
    /* Return an iterator to this source's blocks */
    private Iterator<BalancerBlock> getBlockIterator() {
      return srcBlockList.iterator();
    }
    
    /* fetch new blocks of this source from namenode and
     * update this source's block list & the global block list
     * Return the total size of the received blocks in the number of bytes.
     */
    private long getBlockList() throws IOException {
      final long size = Math.min(MAX_BLOCKS_SIZE_TO_FETCH, blocksToReceive);
      final BlockWithLocations[] newBlocks = nnc.getNamenode().getBlocks(
          getDatanode(), size).getBlocks();

      long bytesReceived = 0;
      for (BlockWithLocations blk : newBlocks) {
        bytesReceived += blk.getBlock().getNumBytes();
        BalancerBlock block;
        synchronized(globalBlockList) {
          block = globalBlockList.get(blk.getBlock());
          if (block==null) {
            block = new BalancerBlock(blk.getBlock());
            globalBlockList.put(blk.getBlock(), block);
          } else {
            block.clearLocations();
          }
        
          synchronized (block) {
            // update locations
            final String[] datanodeUuids = blk.getDatanodeUuids();
            final StorageType[] storageTypes = blk.getStorageTypes();
            for (int i = 0; i < datanodeUuids.length; i++) {
              final BalancerDatanode.StorageGroup g = storageGroupMap.get(
                  datanodeUuids[i], storageTypes[i]);
              if (g != null) { // not unknown
                block.addLocation(g);
              }
            }
          }
          if (!srcBlockList.contains(block) && isGoodBlockCandidate(block)) {
            // filter bad candidates
            srcBlockList.add(block);
          }
        }
      }
      return bytesReceived;
    }

    /* Decide if the given block is a good candidate to move or not */
    private boolean isGoodBlockCandidate(BalancerBlock block) {
      for (Task t : tasks) {
        if (Balancer.this.isGoodBlockCandidate(this, t.target, block)) {
          return true;
        }
      }
      return false;
    }

    /* Return a block that's good for the source thread to dispatch immediately
     * The block's source, target, and proxy source are determined too.
     * When choosing proxy and target, source & target throttling
     * has been considered. They are chosen only when they have the capacity
     * to support this block move.
     * The block should be dispatched immediately after this method is returned.
     */
    private PendingBlockMove chooseNextBlockToMove() {
      for (Iterator<Task> i = tasks.iterator(); i.hasNext();) {
        final Task task = i.next();
        final BalancerDatanode target = task.target.getBalancerDatanode();
        PendingBlockMove pendingBlock = new PendingBlockMove();
        if (target.addPendingBlock(pendingBlock)) { 
          // target is not busy, so do a tentative block allocation
          pendingBlock.source = this;
          pendingBlock.target = task.target;
          if ( pendingBlock.chooseBlockAndProxy() ) {
            long blockSize = pendingBlock.block.getNumBytes();
            incScheduledSize(-blockSize);
            task.size -= blockSize;
            if (task.size == 0) {
              i.remove();
            }
            return pendingBlock;
          } else {
            // cancel the tentative move
            target.removePendingBlock(pendingBlock);
          }
        }
      }
      return null;
    }

    /* iterate all source's blocks to remove moved ones */    
    private void filterMovedBlocks() {
      for (Iterator<BalancerBlock> blocks=getBlockIterator();
            blocks.hasNext();) {
        if (movedBlocks.contains(blocks.next().getBlock())) {
          blocks.remove();
        }
      }
    }
    
    private static final int SOURCE_BLOCK_LIST_MIN_SIZE=5;
    /* Return if should fetch more blocks from namenode */
    private boolean shouldFetchMoreBlocks() {
      return srcBlockList.size()<SOURCE_BLOCK_LIST_MIN_SIZE &&
                 blocksToReceive>0;
    }
    
    /* This method iteratively does the following:
     * it first selects a block to move,
     * then sends a request to the proxy source to start the block move
     * when the source's block list falls below a threshold, it asks
     * the namenode for more blocks.
     * It terminates when it has dispatch enough block move tasks or
     * it has received enough blocks from the namenode, or 
     * the elapsed time of the iteration has exceeded the max time limit.
     */ 
    private static final long MAX_ITERATION_TIME = 20*60*1000L; //20 mins
    private void dispatchBlocks() {
      long startTime = Time.now();
      long scheduledSize = getScheduledSize();
      this.blocksToReceive = 2*scheduledSize;
      boolean isTimeUp = false;
      int noPendingBlockIteration = 0;
      while(!isTimeUp && getScheduledSize()>0 &&
          (!srcBlockList.isEmpty() || blocksToReceive>0)) {
        PendingBlockMove pendingBlock = chooseNextBlockToMove();
        if (pendingBlock != null) {
          // move the block
          pendingBlock.scheduleBlockMove();
          continue;
        }
        
        /* Since we can not schedule any block to move,
         * filter any moved blocks from the source block list and
         * check if we should fetch more blocks from the namenode
         */
        filterMovedBlocks(); // filter already moved blocks
        if (shouldFetchMoreBlocks()) {
          // fetch new blocks
          try {
            blocksToReceive -= getBlockList();
            continue;
          } catch (IOException e) {
            LOG.warn("Exception while getting block list", e);
            return;
          }
        } else {
          // source node cannot find a pendingBlockToMove, iteration +1
          noPendingBlockIteration++;
          // in case no blocks can be moved for source node's task,
          // jump out of while-loop after 5 iterations.
          if (noPendingBlockIteration >= MAX_NO_PENDING_BLOCK_ITERATIONS) {
            resetScheduledSize();
          }
        }
        
        // check if time is up or not
        if (Time.now()-startTime > MAX_ITERATION_TIME) {
          isTimeUp = true;
          continue;
        }
        
        /* Now we can not schedule any block to move and there are
         * no new blocks added to the source block list, so we wait. 
         */
        try {
          synchronized(Balancer.this) {
            Balancer.this.wait(1000);  // wait for targets/sources to be idle
          }
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /* Check that this Balancer is compatible with the Block Placement Policy
   * used by the Namenode.
   */
  private static void checkReplicationPolicyCompatibility(Configuration conf
      ) throws UnsupportedActionException {
    if (!(BlockPlacementPolicy.getInstance(conf, null, null, null) instanceof 
        BlockPlacementPolicyDefault)) {
      throw new UnsupportedActionException(
          "Balancer without BlockPlacementPolicyDefault");
    }
  }

  /**
   * Construct a balancer.
   * Initialize balancer. It sets the value of the threshold, and 
   * builds the communication proxies to
   * namenode as a client and a secondary namenode and retry proxies
   * when connection fails.
   */
  Balancer(NameNodeConnector theblockpool, Parameters p, Configuration conf) {
    this.threshold = p.threshold;
    this.policy = p.policy;
    this.nodesToBeExcluded = p.nodesToBeExcluded;
    this.nodesToBeIncluded = p.nodesToBeIncluded;
    this.nnc = theblockpool;
    this.keyManager = nnc.getKeyManager();
    
    final long movedWinWidth = conf.getLong(
        DFSConfigKeys.DFS_BALANCER_MOVEDWINWIDTH_KEY, 
        DFSConfigKeys.DFS_BALANCER_MOVEDWINWIDTH_DEFAULT);
    movedBlocks = new MovedBlocks<BalancerDatanode.StorageGroup>(movedWinWidth);

    cluster = NetworkTopology.getInstance(conf);

    this.moverExecutor = Executors.newFixedThreadPool(
            conf.getInt(DFSConfigKeys.DFS_BALANCER_MOVERTHREADS_KEY,
                        DFSConfigKeys.DFS_BALANCER_MOVERTHREADS_DEFAULT));
    this.dispatcherExecutor = Executors.newFixedThreadPool(
            conf.getInt(DFSConfigKeys.DFS_BALANCER_DISPATCHERTHREADS_KEY,
                        DFSConfigKeys.DFS_BALANCER_DISPATCHERTHREADS_DEFAULT));
    this.maxConcurrentMovesPerNode =
        conf.getInt(DFSConfigKeys.DFS_DATANODE_BALANCE_MAX_NUM_CONCURRENT_MOVES_KEY,
        DFSConfigKeys.DFS_DATANODE_BALANCE_MAX_NUM_CONCURRENT_MOVES_DEFAULT);
    this.saslClient = new SaslDataTransferClient(
      DataTransferSaslUtil.getSaslPropertiesResolver(conf),
      TrustedChannelResolver.getInstance(conf),
      conf.getBoolean(
        IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_KEY,
        IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_DEFAULT));
  }
  
  
  private static long getCapacity(DatanodeStorageReport report, StorageType t) {
    long capacity = 0L;
    for(StorageReport r : report.getStorageReports()) {
      if (r.getStorage().getStorageType() == t) {
        capacity += r.getCapacity();
      }
    }
    return capacity;
  }

  private static long getRemaining(DatanodeStorageReport report, StorageType t) {
    long remaining = 0L;
    for(StorageReport r : report.getStorageReports()) {
      if (r.getStorage().getStorageType() == t) {
        remaining += r.getRemaining();
      }
    }
    return remaining;
  }

  private boolean shouldIgnore(DatanodeInfo dn) {
    //ignore decommissioned nodes
    final boolean decommissioned = dn.isDecommissioned();
    //ignore decommissioning nodes
    final boolean decommissioning = dn.isDecommissionInProgress();
    // ignore nodes in exclude list
    final boolean excluded = Util.shouldBeExcluded(nodesToBeExcluded, dn);
    // ignore nodes not in the include list (if include list is not empty)
    final boolean notIncluded = !Util.shouldBeIncluded(nodesToBeIncluded, dn);
    
    if (decommissioned || decommissioning || excluded || notIncluded) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Excluding datanode " + dn + ": " + decommissioned + ", "
            + decommissioning + ", " + excluded + ", " + notIncluded);
      }
      return true;
    }
    return false;
  }

  /**
   * Given a datanode storage set, build a network topology and decide
   * over-utilized storages, above average utilized storages, 
   * below average utilized storages, and underutilized storages. 
   * The input datanode storage set is shuffled in order to randomize
   * to the storage matching later on.
   *
   * @return the total number of bytes that are 
   *                needed to move to make the cluster balanced.
   * @param reports a set of datanode storage reports
   */
  private long init(DatanodeStorageReport[] reports) {
    // compute average utilization
    for (DatanodeStorageReport r : reports) {
      if (shouldIgnore(r.getDatanodeInfo())) {
        continue; 
      }
      policy.accumulateSpaces(r);
    }
    policy.initAvgUtilization();

    // create network topology and classify utilization collections: 
    //   over-utilized, above-average, below-average and under-utilized.
    long overLoadedBytes = 0L, underLoadedBytes = 0L;
    for(DatanodeStorageReport r : DFSUtil.shuffle(reports)) {
      final DatanodeInfo datanode = r.getDatanodeInfo();
      if (shouldIgnore(datanode)) {
        continue; // ignore decommissioning or decommissioned nodes
      }
      cluster.add(datanode);

      final BalancerDatanode dn = new BalancerDatanode(r, underLoadedBytes,
          maxConcurrentMovesPerNode);
      for(StorageType t : StorageType.asList()) {
        final Double utilization = policy.getUtilization(r, t);
        if (utilization == null) { // datanode does not have such storage type 
          continue;
        }
        
        final long capacity = getCapacity(r, t);
        final double utilizationDiff = utilization - policy.getAvgUtilization(t);
        final double thresholdDiff = Math.abs(utilizationDiff) - threshold;
        final long maxSize2Move = computeMaxSize2Move(capacity,
            getRemaining(r, t), utilizationDiff, threshold);

        final BalancerDatanode.StorageGroup g;
        if (utilizationDiff > 0) {
          final Source s = dn.addSource(t, utilization, maxSize2Move, this);
          if (thresholdDiff <= 0) { // within threshold
            aboveAvgUtilized.add(s);
          } else {
            overLoadedBytes += precentage2bytes(thresholdDiff, capacity);
            overUtilized.add(s);
          }
          g = s;
        } else {
          g = dn.addStorageGroup(t, utilization, maxSize2Move);
          if (thresholdDiff <= 0) { // within threshold
            belowAvgUtilized.add(g);
          } else {
            underLoadedBytes += precentage2bytes(thresholdDiff, capacity);
            underUtilized.add(g);
          }
        }
        storageGroupMap.put(g);
      }
    }

    logUtilizationCollections();
    
    Preconditions.checkState(storageGroupMap.size() == overUtilized.size()
        + underUtilized.size() + aboveAvgUtilized.size() + belowAvgUtilized.size(),
        "Mismatched number of storage groups");
    
    // return number of bytes to be moved in order to make the cluster balanced
    return Math.max(overLoadedBytes, underLoadedBytes);
  }

  private static long computeMaxSize2Move(final long capacity, final long remaining,
      final double utilizationDiff, final double threshold) {
    final double diff = Math.min(threshold, Math.abs(utilizationDiff));
    long maxSizeToMove = precentage2bytes(diff, capacity);
    if (utilizationDiff < 0) {
      maxSizeToMove = Math.min(remaining, maxSizeToMove);
    }
    return Math.min(MAX_SIZE_TO_MOVE, maxSizeToMove);
  }

  private static long precentage2bytes(double precentage, long capacity) {
    Preconditions.checkArgument(precentage >= 0,
        "precentage = " + precentage + " < 0");
    return (long)(precentage * capacity / 100.0);
  }

  /* log the over utilized & under utilized nodes */
  private void logUtilizationCollections() {
    logUtilizationCollection("over-utilized", overUtilized);
    if (LOG.isTraceEnabled()) {
      logUtilizationCollection("above-average", aboveAvgUtilized);
      logUtilizationCollection("below-average", belowAvgUtilized);
    }
    logUtilizationCollection("underutilized", underUtilized);
  }

  private static <T extends BalancerDatanode.StorageGroup>
      void logUtilizationCollection(String name, Collection<T> items) {
    LOG.info(items.size() + " " + name + ": " + items);
  }

  /**
   * Decide all <source, target> pairs and
   * the number of bytes to move from a source to a target
   * Maximum bytes to be moved per storage group is
   * min(1 Band worth of bytes,  MAX_SIZE_TO_MOVE).
   * @return total number of bytes to move in this iteration
   */
  private long chooseStorageGroups() {
    // First, match nodes on the same node group if cluster is node group aware
    if (cluster.isNodeGroupAware()) {
      chooseStorageGroups(Matcher.SAME_NODE_GROUP);
    }
    
    // Then, match nodes on the same rack
    chooseStorageGroups(Matcher.SAME_RACK);
    // At last, match all remaining nodes
    chooseStorageGroups(Matcher.ANY_OTHER);
    
    Preconditions.checkState(storageGroupMap.size() >= sources.size() + targets.size(),
        "Mismatched number of datanodes (" + storageGroupMap.size() + " < "
            + sources.size() + " sources, " + targets.size() + " targets)");

    long bytesToMove = 0L;
    for (Source src : sources) {
      bytesToMove += src.getScheduledSize();
    }
    return bytesToMove;
  }

  /** Decide all <source, target> pairs according to the matcher. */
  private void chooseStorageGroups(final Matcher matcher) {
    /* first step: match each overUtilized datanode (source) to
     * one or more underUtilized datanodes (targets).
     */
    chooseStorageGroups(overUtilized, underUtilized, matcher);
    
    /* match each remaining overutilized datanode (source) to 
     * below average utilized datanodes (targets).
     * Note only overutilized datanodes that haven't had that max bytes to move
     * satisfied in step 1 are selected
     */
    chooseStorageGroups(overUtilized, belowAvgUtilized, matcher);

    /* match each remaining underutilized datanode (target) to 
     * above average utilized datanodes (source).
     * Note only underutilized datanodes that have not had that max bytes to
     * move satisfied in step 1 are selected.
     */
    chooseStorageGroups(underUtilized, aboveAvgUtilized, matcher);
  }

  /**
   * For each datanode, choose matching nodes from the candidates. Either the
   * datanodes or the candidates are source nodes with (utilization > Avg), and
   * the others are target nodes with (utilization < Avg).
   */
  private <G extends BalancerDatanode.StorageGroup,
           C extends BalancerDatanode.StorageGroup>
      void chooseStorageGroups(Collection<G> groups, Collection<C> candidates,
          Matcher matcher) {
    for(final Iterator<G> i = groups.iterator(); i.hasNext();) {
      final G g = i.next();
      for(; choose4One(g, candidates, matcher); );
      if (!g.hasSpaceForScheduling()) {
        i.remove();
      }
    }
  }

  /**
   * For the given datanode, choose a candidate and then schedule it.
   * @return true if a candidate is chosen; false if no candidates is chosen.
   */
  private <C extends BalancerDatanode.StorageGroup>
      boolean choose4One(BalancerDatanode.StorageGroup g,
          Collection<C> candidates, Matcher matcher) {
    final Iterator<C> i = candidates.iterator();
    final C chosen = chooseCandidate(g, i, matcher);
  
    if (chosen == null) {
      return false;
    }
    if (g instanceof Source) {
      matchSourceWithTargetToMove((Source)g, chosen);
    } else {
      matchSourceWithTargetToMove((Source)chosen, g);
    }
    if (!chosen.hasSpaceForScheduling()) {
      i.remove();
    }
    return true;
  }
  
  private void matchSourceWithTargetToMove(Source source,
      BalancerDatanode.StorageGroup target) {
    long size = Math.min(source.availableSizeToMove(), target.availableSizeToMove());
    final Task task = new Task(target, size);
    source.addTask(task);
    target.incScheduledSize(task.size);
    sources.add(source);
    targets.add(target);
    LOG.info("Decided to move "+StringUtils.byteDesc(size)+" bytes from "
        + source.getDisplayName() + " to " + target.getDisplayName());
  }
  
  /** Choose a candidate for the given datanode. */
  private <G extends BalancerDatanode.StorageGroup,
           C extends BalancerDatanode.StorageGroup>
      C chooseCandidate(G g, Iterator<C> candidates, Matcher matcher) {
    if (g.hasSpaceForScheduling()) {
      for(; candidates.hasNext(); ) {
        final C c = candidates.next();
        if (!c.hasSpaceForScheduling()) {
          candidates.remove();
        } else if (matcher.match(cluster, g.getDatanode(), c.getDatanode())) {
          return c;
        }
      }
    }
    return null;
  }

  private final AtomicLong bytesMoved = new AtomicLong();
  
  /* Start a thread to dispatch block moves for each source. 
   * The thread selects blocks to move & sends request to proxy source to
   * initiate block move. The process is flow controlled. Block selection is
   * blocked if there are too many un-confirmed block moves.
   * Return the total number of bytes successfully moved in this iteration.
   */
  private long dispatchBlockMoves() throws InterruptedException {
    long bytesLastMoved = bytesMoved.get();
    Future<?>[] futures = new Future<?>[sources.size()];
    int i=0;
    for (Source source : sources) {
      futures[i++] = dispatcherExecutor.submit(source.new BlockMoveDispatcher());
    }
    
    // wait for all dispatcher threads to finish
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        LOG.warn("Dispatcher thread failed", e.getCause());
      }
    }
    
    // wait for all block moving to be done
    waitForMoveCompletion();
    
    return bytesMoved.get()-bytesLastMoved;
  }
  
  // The sleeping period before checking if block move is completed again
  static private long blockMoveWaitTime = 30000L;
  
  /** set the sleeping period for block move completion check */
  static void setBlockMoveWaitTime(long time) {
    blockMoveWaitTime = time;
  }
  
  /* wait for all block move confirmations 
   * by checking each target's pendingMove queue 
   */
  private void waitForMoveCompletion() {
    boolean shouldWait;
    do {
      shouldWait = false;
      for (BalancerDatanode.StorageGroup target : targets) {
        if (!target.getBalancerDatanode().isPendingQEmpty()) {
          shouldWait = true;
          break;
        }
      }
      if (shouldWait) {
        try {
          Thread.sleep(blockMoveWaitTime);
        } catch (InterruptedException ignored) {
        }
      }
    } while (shouldWait);
  }

  /* Decide if it is OK to move the given block from source to target
   * A block is a good candidate if
   * 1. the block is not in the process of being moved/has not been moved;
   * 2. the block does not have a replica on the target;
   * 3. doing the move does not reduce the number of racks that the block has
   */
  private boolean isGoodBlockCandidate(Source source, 
      BalancerDatanode.StorageGroup target, BalancerBlock block) {
    if (source.storageType != target.storageType) {
      return false;
    }
    // check if the block is moved or not
    if (movedBlocks.contains(block.getBlock())) {
      return false;
    }
    if (block.isLocatedOn(target)) {
      return false;
    }
    if (cluster.isNodeGroupAware() && 
        isOnSameNodeGroupWithReplicas(target, block, source)) {
      return false;
    }

    boolean goodBlock = false;
    if (cluster.isOnSameRack(source.getDatanode(), target.getDatanode())) {
      // good if source and target are on the same rack
      goodBlock = true;
    } else {
      boolean notOnSameRack = true;
      synchronized (block) {
        for (BalancerDatanode.StorageGroup loc : block.getLocations()) {
          if (cluster.isOnSameRack(loc.getDatanode(), target.getDatanode())) {
            notOnSameRack = false;
            break;
          }
        }
      }
      if (notOnSameRack) {
        // good if target is target is not on the same rack as any replica
        goodBlock = true;
      } else {
        // good if source is on the same rack as on of the replicas
        for (BalancerDatanode.StorageGroup loc : block.getLocations()) {
          if (loc != source && 
              cluster.isOnSameRack(loc.getDatanode(), source.getDatanode())) {
            goodBlock = true;
            break;
          }
        }
      }
    }
    return goodBlock;
  }

  /**
   * Check if there are any replica (other than source) on the same node group
   * with target. If true, then target is not a good candidate for placing 
   * specific block replica as we don't want 2 replicas under the same nodegroup 
   * after balance.
   * @param target targetDataNode
   * @param block dataBlock
   * @param source sourceDataNode
   * @return true if there are any replica (other than source) on the same node
   * group with target
   */
  private boolean isOnSameNodeGroupWithReplicas(BalancerDatanode.StorageGroup target,
      BalancerBlock block, Source source) {
    final DatanodeInfo targetDn = target.getDatanode();
    for (BalancerDatanode.StorageGroup loc : block.getLocations()) {
      if (loc != source && 
          cluster.isOnSameNodeGroup(loc.getDatanode(), targetDn)) {
        return true;
      }
    }
    return false;
  }

  /* reset all fields in a balancer preparing for the next iteration */
  private void resetData(Configuration conf) {
    this.cluster = NetworkTopology.getInstance(conf);
    this.overUtilized.clear();
    this.aboveAvgUtilized.clear();
    this.belowAvgUtilized.clear();
    this.underUtilized.clear();
    this.storageGroupMap.clear();
    this.sources.clear();
    this.targets.clear();  
    this.policy.reset();
    cleanGlobalBlockList();
    this.movedBlocks.cleanup();
  }
  
  /* Remove all blocks from the global block list except for the ones in the
   * moved list.
   */
  private void cleanGlobalBlockList() {
    for (Iterator<Block> globalBlockListIterator=globalBlockList.keySet().iterator();
    globalBlockListIterator.hasNext();) {
      Block block = globalBlockListIterator.next();
      if(!movedBlocks.contains(block)) {
        globalBlockListIterator.remove();
      }
    }
  }

  // Exit status
  enum ReturnStatus {
    // These int values will map directly to the balancer process's exit code.
    SUCCESS(0),
    IN_PROGRESS(1),
    ALREADY_RUNNING(-1),
    NO_MOVE_BLOCK(-2),
    NO_MOVE_PROGRESS(-3),
    IO_EXCEPTION(-4),
    ILLEGAL_ARGS(-5),
    INTERRUPTED(-6);

    final int code;

    ReturnStatus(int code) {
      this.code = code;
    }
  }

  /** Run an iteration for all datanodes. */
  private ReturnStatus run(int iteration, Formatter formatter,
      Configuration conf) {
    try {
      /* get all live datanodes of a cluster and their disk usage
       * decide the number of bytes need to be moved
       */
      final long bytesLeftToMove = init(
          nnc.getClient().getDatanodeStorageReport(DatanodeReportType.LIVE));
      if (bytesLeftToMove == 0) {
        System.out.println("The cluster is balanced. Exiting...");
        return ReturnStatus.SUCCESS;
      } else {
        LOG.info( "Need to move "+ StringUtils.byteDesc(bytesLeftToMove)
            + " to make the cluster balanced." );
      }
      
      /* Decide all the nodes that will participate in the block move and
       * the number of bytes that need to be moved from one node to another
       * in this iteration. Maximum bytes to be moved per node is
       * Min(1 Band worth of bytes,  MAX_SIZE_TO_MOVE).
       */
      final long bytesToMove = chooseStorageGroups();
      if (bytesToMove == 0) {
        System.out.println("No block can be moved. Exiting...");
        return ReturnStatus.NO_MOVE_BLOCK;
      } else {
        LOG.info( "Will move " + StringUtils.byteDesc(bytesToMove) +
            " in this iteration");
      }

      formatter.format("%-24s %10d  %19s  %18s  %17s%n",
          DateFormat.getDateTimeInstance().format(new Date()),
          iteration,
          StringUtils.byteDesc(bytesMoved.get()),
          StringUtils.byteDesc(bytesLeftToMove),
          StringUtils.byteDesc(bytesToMove)
          );
      
      /* For each pair of <source, target>, start a thread that repeatedly 
       * decide a block to be moved and its proxy source, 
       * then initiates the move until all bytes are moved or no more block
       * available to move.
       * Exit no byte has been moved for 5 consecutive iterations.
       */
      if (!this.nnc.shouldContinue(dispatchBlockMoves())) {
        return ReturnStatus.NO_MOVE_PROGRESS;
      }

      return ReturnStatus.IN_PROGRESS;
    } catch (IllegalArgumentException e) {
      System.out.println(e + ".  Exiting ...");
      return ReturnStatus.ILLEGAL_ARGS;
    } catch (IOException e) {
      System.out.println(e + ".  Exiting ...");
      return ReturnStatus.IO_EXCEPTION;
    } catch (InterruptedException e) {
      System.out.println(e + ".  Exiting ...");
      return ReturnStatus.INTERRUPTED;
    } finally {
      // shutdown thread pools
      dispatcherExecutor.shutdownNow();
      moverExecutor.shutdownNow();
    }
  }

  /**
   * Balance all namenodes.
   * For each iteration,
   * for each namenode,
   * execute a {@link Balancer} to work through all datanodes once.  
   */
  static int run(Collection<URI> namenodes, final Parameters p,
      Configuration conf) throws IOException, InterruptedException {
    final long sleeptime = 2000*conf.getLong(
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY,
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_DEFAULT);
    LOG.info("namenodes  = " + namenodes);
    LOG.info("parameters = " + p);
    
    final Formatter formatter = new Formatter(System.out);
    System.out.println("Time Stamp               Iteration#  Bytes Already Moved  Bytes Left To Move  Bytes Being Moved");
    
    final List<NameNodeConnector> connectors
        = new ArrayList<NameNodeConnector>(namenodes.size());
    try {
      for (URI uri : namenodes) {
        final NameNodeConnector nnc = new NameNodeConnector(
            Balancer.class.getSimpleName(), uri, BALANCER_ID_PATH, conf);
        nnc.getKeyManager().startBlockKeyUpdater();
        connectors.add(nnc);
      }
    
      boolean done = false;
      for(int iteration = 0; !done; iteration++) {
        done = true;
        Collections.shuffle(connectors);
        for(NameNodeConnector nnc : connectors) {
          final Balancer b = new Balancer(nnc, p, conf);
          final ReturnStatus r = b.run(iteration, formatter, conf);
          // clean all lists
          b.resetData(conf);
          if (r == ReturnStatus.IN_PROGRESS) {
            done = false;
          } else if (r != ReturnStatus.SUCCESS) {
            //must be an error statue, return.
            return r.code;
          }
        }

        if (!done) {
          Thread.sleep(sleeptime);
        }
      }
    } finally {
      for(NameNodeConnector nnc : connectors) {
        nnc.close();
      }
    }
    return ReturnStatus.SUCCESS.code;
  }

  /* Given elaspedTime in ms, return a printable string */
  private static String time2Str(long elapsedTime) {
    String unit;
    double time = elapsedTime;
    if (elapsedTime < 1000) {
      unit = "milliseconds";
    } else if (elapsedTime < 60*1000) {
      unit = "seconds";
      time = time/1000;
    } else if (elapsedTime < 3600*1000) {
      unit = "minutes";
      time = time/(60*1000);
    } else {
      unit = "hours";
      time = time/(3600*1000);
    }

    return time+" "+unit;
  }

  static class Parameters {
    static final Parameters DEFAULT = new Parameters(
        BalancingPolicy.Node.INSTANCE, 10.0,
        Collections.<String> emptySet(), Collections.<String> emptySet());

    final BalancingPolicy policy;
    final double threshold;
    // exclude the nodes in this set from balancing operations
    Set<String> nodesToBeExcluded;
    //include only these nodes in balancing operations
    Set<String> nodesToBeIncluded;

    Parameters(BalancingPolicy policy, double threshold,
        Set<String> nodesToBeExcluded, Set<String> nodesToBeIncluded) {
      this.policy = policy;
      this.threshold = threshold;
      this.nodesToBeExcluded = nodesToBeExcluded;
      this.nodesToBeIncluded = nodesToBeIncluded;
    }

    @Override
    public String toString() {
      return Balancer.class.getSimpleName() + "." + getClass().getSimpleName()
          + "[" + policy + ", threshold=" + threshold +
          ", number of nodes to be excluded = "+ nodesToBeExcluded.size() +
          ", number of nodes to be included = "+ nodesToBeIncluded.size() +"]";
    }
  }

  static class Util {

    /**
     * @param datanode
     * @return returns true if data node is part of the excludedNodes.
     */
    static boolean shouldBeExcluded(Set<String> excludedNodes, DatanodeInfo datanode) {
      return isIn(excludedNodes, datanode);
    }

    /**
     * @param datanode
     * @return returns true if includedNodes is empty or data node is part of the includedNodes.
     */
    static boolean shouldBeIncluded(Set<String> includedNodes, DatanodeInfo datanode) {
      return (includedNodes.isEmpty() ||
          isIn(includedNodes, datanode));
    }
    /**
     * Match is checked using host name , ip address with and without port number.
     * @param datanodeSet
     * @param datanode
     * @return true if the datanode's transfer address matches the set of nodes.
     */
    private static boolean isIn(Set<String> datanodeSet, DatanodeInfo datanode) {
      return isIn(datanodeSet, datanode.getPeerHostName(), datanode.getXferPort()) ||
          isIn(datanodeSet, datanode.getIpAddr(), datanode.getXferPort()) ||
          isIn(datanodeSet, datanode.getHostName(), datanode.getXferPort());
    }

    /**
     * returns true if nodes contains host or host:port
     * @param nodes
     * @param host
     * @param port
     * @return
     */
    private static boolean isIn(Set<String> nodes, String host, int port) {
      if (host == null) {
        return false;
      }
      return (nodes.contains(host) || nodes.contains(host +":"+ port));
    }

    /**
     * parse a comma separated string to obtain set of host names
     * @param string
     * @return
     */
    static Set<String> parseHostList(String string) {
      String[] addrs = StringUtils.getTrimmedStrings(string);
      return new HashSet<String>(Arrays.asList(addrs));
    }

    /**
     * read set of host names from a file
     * @param fileName
     * @return
     */
    static Set<String> getHostListFromFile(String fileName) {
      Set<String> nodes = new HashSet <String> ();
      try {
        HostsFileReader.readFileToSet("nodes", fileName, nodes);
        return StringUtils.getTrimmedStrings(nodes);
      } catch (IOException e) {
        throw new IllegalArgumentException("Unable to open file: " + fileName);
      }
    }
  }

  static class Cli extends Configured implements Tool {
    /**
     * Parse arguments and then run Balancer.
     * 
     * @param args command specific arguments.
     * @return exit code. 0 indicates success, non-zero indicates failure.
     */
    @Override
    public int run(String[] args) {
      final long startTime = Time.now();
      final Configuration conf = getConf();

      try {
        checkReplicationPolicyCompatibility(conf);

        final Collection<URI> namenodes = DFSUtil.getNsServiceRpcUris(conf);
        return Balancer.run(namenodes, parse(args), conf);
      } catch (IOException e) {
        System.out.println(e + ".  Exiting ...");
        return ReturnStatus.IO_EXCEPTION.code;
      } catch (InterruptedException e) {
        System.out.println(e + ".  Exiting ...");
        return ReturnStatus.INTERRUPTED.code;
      } finally {
        System.out.format("%-24s ", DateFormat.getDateTimeInstance().format(new Date()));
        System.out.println("Balancing took " + time2Str(Time.now()-startTime));
      }
    }

    /** parse command line arguments */
    static Parameters parse(String[] args) {
      BalancingPolicy policy = Parameters.DEFAULT.policy;
      double threshold = Parameters.DEFAULT.threshold;
      Set<String> nodesTobeExcluded = Parameters.DEFAULT.nodesToBeExcluded;
      Set<String> nodesTobeIncluded = Parameters.DEFAULT.nodesToBeIncluded;

      if (args != null) {
        try {
          for(int i = 0; i < args.length; i++) {
            if ("-threshold".equalsIgnoreCase(args[i])) {
              checkArgument(++i < args.length,
                "Threshold value is missing: args = " + Arrays.toString(args));
              try {
                threshold = Double.parseDouble(args[i]);
                if (threshold < 1 || threshold > 100) {
                  throw new IllegalArgumentException(
                      "Number out of range: threshold = " + threshold);
                }
                LOG.info( "Using a threshold of " + threshold );
              } catch(IllegalArgumentException e) {
                System.err.println(
                    "Expecting a number in the range of [1.0, 100.0]: "
                    + args[i]);
                throw e;
              }
            } else if ("-policy".equalsIgnoreCase(args[i])) {
              checkArgument(++i < args.length,
                "Policy value is missing: args = " + Arrays.toString(args));
              try {
                policy = BalancingPolicy.parse(args[i]);
              } catch(IllegalArgumentException e) {
                System.err.println("Illegal policy name: " + args[i]);
                throw e;
              }
            } else if ("-exclude".equalsIgnoreCase(args[i])) {
              checkArgument(++i < args.length,
                  "List of nodes to exclude | -f <filename> is missing: args = "
                  + Arrays.toString(args));
              if ("-f".equalsIgnoreCase(args[i])) {
                checkArgument(++i < args.length,
                    "File containing nodes to exclude is not specified: args = "
                    + Arrays.toString(args));
                nodesTobeExcluded = Util.getHostListFromFile(args[i]);
              } else {
                nodesTobeExcluded = Util.parseHostList(args[i]);
              }
            } else if ("-include".equalsIgnoreCase(args[i])) {
              checkArgument(++i < args.length,
                "List of nodes to include | -f <filename> is missing: args = "
                + Arrays.toString(args));
              if ("-f".equalsIgnoreCase(args[i])) {
                checkArgument(++i < args.length,
                    "File containing nodes to include is not specified: args = "
                    + Arrays.toString(args));
                nodesTobeIncluded = Util.getHostListFromFile(args[i]);
               } else {
                nodesTobeIncluded = Util.parseHostList(args[i]);
              }
            } else {
              throw new IllegalArgumentException("args = "
                  + Arrays.toString(args));
            }
          }
          checkArgument(nodesTobeExcluded.isEmpty() || nodesTobeIncluded.isEmpty(),
              "-exclude and -include options cannot be specified together.");
        } catch(RuntimeException e) {
          printUsage(System.err);
          throw e;
        }
      }
      
      return new Parameters(policy, threshold, nodesTobeExcluded, nodesTobeIncluded);
    }

    private static void printUsage(PrintStream out) {
      out.println(USAGE + "\n");
    }
  }

  /**
   * Run a balancer
   * @param args Command line arguments
   */
  public static void main(String[] args) {
    if (DFSUtil.parseHelpArgument(args, USAGE, System.out, true)) {
      System.exit(0);
    }

    try {
      System.exit(ToolRunner.run(new HdfsConfiguration(), new Cli(), args));
    } catch (Throwable e) {
      LOG.error("Exiting balancer due an exception", e);
      System.exit(-1);
    }
  }
}
