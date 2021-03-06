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

package org.apache.hadoop.hbase.backup.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupRestoreFactory;
import org.apache.hadoop.hbase.backup.HBackupFileSystem;
import org.apache.hadoop.hbase.backup.RestoreJob;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.regionserver.HRegionFileSystem;
import org.apache.hadoop.hbase.regionserver.HStore;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.snapshot.SnapshotDescriptionUtils;
import org.apache.hadoop.hbase.snapshot.SnapshotManifest;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSTableDescriptors;

/**
 * A collection for methods used by multiple classes to restore HBase tables.
 */
@InterfaceAudience.Private
public class RestoreTool {

  public static final Log LOG = LogFactory.getLog(BackupUtils.class);

  private final String[] ignoreDirs = { HConstants.RECOVERED_EDITS_DIR };

  private final static long TABLE_AVAILABILITY_WAIT_TIME = 180000;

  protected Configuration conf = null;

  protected Path backupRootPath;

  protected String backupId;

  protected FileSystem fs;
  private final Path restoreTmpPath;

  // store table name and snapshot dir mapping
  private final HashMap<TableName, Path> snapshotMap = new HashMap<>();

  public RestoreTool(Configuration conf, final Path backupRootPath, final String backupId)
      throws IOException {
    this.conf = conf;
    this.backupRootPath = backupRootPath;
    this.backupId = backupId;
    this.fs = backupRootPath.getFileSystem(conf);
    this.restoreTmpPath =
        new Path(conf.get(HConstants.TEMPORARY_FS_DIRECTORY_KEY,
          HConstants.DEFAULT_TEMPORARY_HDFS_DIRECTORY), "restore");
  }

  /**
   * return value represent path for:
   * ".../user/biadmin/backup1/default/t1_dn/backup_1396650096738/archive/data/default/t1_dn"
   * @param tabelName table name
   * @return path to table archive
   * @throws IOException exception
   */
  Path getTableArchivePath(TableName tableName) throws IOException {

    Path baseDir =
        new Path(HBackupFileSystem.getTableBackupPath(tableName, backupRootPath, backupId),
            HConstants.HFILE_ARCHIVE_DIRECTORY);
    Path dataDir = new Path(baseDir, HConstants.BASE_NAMESPACE_DIR);
    Path archivePath = new Path(dataDir, tableName.getNamespaceAsString());
    Path tableArchivePath = new Path(archivePath, tableName.getQualifierAsString());
    if (!fs.exists(tableArchivePath) || !fs.getFileStatus(tableArchivePath).isDirectory()) {
      LOG.debug("Folder tableArchivePath: " + tableArchivePath.toString() + " does not exists");
      tableArchivePath = null; // empty table has no archive
    }
    return tableArchivePath;
  }

  /**
   * Gets region list
   * @param tableName table name
   * @return RegionList region list
   * @throws FileNotFoundException exception
   * @throws IOException exception
   */
  ArrayList<Path> getRegionList(TableName tableName) throws FileNotFoundException, IOException {
    Path tableArchivePath = getTableArchivePath(tableName);
    ArrayList<Path> regionDirList = new ArrayList<Path>();
    FileStatus[] children = fs.listStatus(tableArchivePath);
    for (FileStatus childStatus : children) {
      // here child refer to each region(Name)
      Path child = childStatus.getPath();
      regionDirList.add(child);
    }
    return regionDirList;
  }


  void modifyTableSync(Connection conn, HTableDescriptor desc) throws IOException {

    try (Admin admin = conn.getAdmin();) {
      admin.modifyTable(desc.getTableName(), desc);
      int attempt = 0;
      int maxAttempts = 600;
      while (!admin.isTableAvailable(desc.getTableName())) {
        Thread.sleep(100);
        attempt++;
        if (attempt++ > maxAttempts) {
          throw new IOException("Timeout expired " + (maxAttempts * 100) + "ms");
        }
      }
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  /**
   * During incremental backup operation. Call WalPlayer to replay WAL in backup image Currently
   * tableNames and newTablesNames only contain single table, will be expanded to multiple tables in
   * the future
   * @param conn HBase connection
   * @param tableBackupPath backup path
   * @param logDirs : incremental backup folders, which contains WAL
   * @param tableNames : source tableNames(table names were backuped)
   * @param newTableNames : target tableNames(table names to be restored to)
   * @param incrBackupId incremental backup Id
   * @throws IOException exception
   */
  public void incrementalRestoreTable(Connection conn, Path tableBackupPath, Path[] logDirs,
      TableName[] tableNames, TableName[] newTableNames, String incrBackupId) throws IOException {

    try (Admin admin = conn.getAdmin();) {
      if (tableNames.length != newTableNames.length) {
        throw new IOException("Number of source tables and target tables does not match!");
      }
      FileSystem fileSys = tableBackupPath.getFileSystem(this.conf);

      // for incremental backup image, expect the table already created either by user or previous
      // full backup. Here, check that all new tables exists
      for (TableName tableName : newTableNames) {
        if (!admin.tableExists(tableName)) {
          throw new IOException("HBase table " + tableName
              + " does not exist. Create the table first, e.g. by restoring a full backup.");
        }
      }
      // adjust table schema
      for (int i = 0; i < tableNames.length; i++) {
        TableName tableName = tableNames[i];
        HTableDescriptor tableDescriptor = getTableDescriptor(fileSys, tableName, incrBackupId);
        LOG.debug("Found descriptor " + tableDescriptor + " through " + incrBackupId);

        TableName newTableName = newTableNames[i];
        HTableDescriptor newTableDescriptor = admin.getTableDescriptor(newTableName);
        List<HColumnDescriptor> families = Arrays.asList(tableDescriptor.getColumnFamilies());
        List<HColumnDescriptor> existingFamilies =
            Arrays.asList(newTableDescriptor.getColumnFamilies());
        boolean schemaChangeNeeded = false;
        for (HColumnDescriptor family : families) {
          if (!existingFamilies.contains(family)) {
            newTableDescriptor.addFamily(family);
            schemaChangeNeeded = true;
          }
        }
        for (HColumnDescriptor family : existingFamilies) {
          if (!families.contains(family)) {
            newTableDescriptor.removeFamily(family.getName());
            schemaChangeNeeded = true;
          }
        }
        if (schemaChangeNeeded) {
          modifyTableSync(conn, newTableDescriptor);
          LOG.info("Changed " + newTableDescriptor.getTableName() + " to: " + newTableDescriptor);
        }
      }
      RestoreJob restoreService = BackupRestoreFactory.getRestoreJob(conf);

      restoreService.run(logDirs, tableNames, newTableNames, false);
    }
  }

  public void fullRestoreTable(Connection conn, Path tableBackupPath, TableName tableName,
      TableName newTableName, boolean truncateIfExists, String lastIncrBackupId)
          throws IOException {
    restoreTableAndCreate(conn, tableName, newTableName, tableBackupPath, truncateIfExists,
      lastIncrBackupId);
  }

  /**
   * Returns value represent path for path to backup table snapshot directory:
   * "/$USER/SBACKUP_ROOT/backup_id/namespace/table/.hbase-snapshot"
   * @param backupRootPath backup root path
   * @param tableName table name
   * @param backupId backup Id
   * @return path for snapshot
   */
  Path getTableSnapshotPath(Path backupRootPath, TableName tableName, String backupId) {
    return new Path(HBackupFileSystem.getTableBackupPath(tableName, backupRootPath, backupId),
        HConstants.SNAPSHOT_DIR_NAME);
  }

  /**
   * Returns value represent path for:
   * ""/$USER/SBACKUP_ROOT/backup_id/namespace/table/.hbase-snapshot/snapshot_1396650097621_namespace_table"
   * this path contains .snapshotinfo, .tabledesc (0.96 and 0.98) this path contains .snapshotinfo,
   * .data.manifest (trunk)
   * @param tableName table name
   * @return path to table info
   * @throws FileNotFoundException exception
   * @throws IOException exception
   */
  Path getTableInfoPath(TableName tableName) throws FileNotFoundException, IOException {
    Path tableSnapShotPath = getTableSnapshotPath(backupRootPath, tableName, backupId);
    Path tableInfoPath = null;

    // can't build the path directly as the timestamp values are different
    FileStatus[] snapshots = fs.listStatus(tableSnapShotPath);
    for (FileStatus snapshot : snapshots) {
      tableInfoPath = snapshot.getPath();
      // SnapshotManifest.DATA_MANIFEST_NAME = "data.manifest";
      if (tableInfoPath.getName().endsWith("data.manifest")) {
        break;
      }
    }
    return tableInfoPath;
  }

  /**
   * Get table descriptor
   * @param tableName is the table backed up
   * @return {@link HTableDescriptor} saved in backup image of the table
   */
  HTableDescriptor getTableDesc(TableName tableName) throws FileNotFoundException, IOException {
    Path tableInfoPath = this.getTableInfoPath(tableName);
    SnapshotDescription desc = SnapshotDescriptionUtils.readSnapshotInfo(fs, tableInfoPath);
    SnapshotManifest manifest = SnapshotManifest.open(conf, fs, tableInfoPath, desc);
    HTableDescriptor tableDescriptor = manifest.getTableDescriptor();
    if (!tableDescriptor.getTableName().equals(tableName)) {
      LOG.error("couldn't find Table Desc for table: " + tableName + " under tableInfoPath: "
          + tableInfoPath.toString());
      LOG.error("tableDescriptor.getNameAsString() = " + tableDescriptor.getNameAsString());
      throw new FileNotFoundException("couldn't find Table Desc for table: " + tableName
          + " under tableInfoPath: " + tableInfoPath.toString());
    }
    return tableDescriptor;
  }

  /**
   * Duplicate the backup image if it's on local cluster
   * @see HStore#bulkLoadHFile(String, long)
   * @see HRegionFileSystem#bulkLoadStoreFile(String familyName, Path srcPath, long seqNum)
   * @param tableArchivePath archive path
   * @return the new tableArchivePath
   * @throws IOException exception
   */
  Path checkLocalAndBackup(Path tableArchivePath) throws IOException {
    // Move the file if it's on local cluster
    boolean isCopyNeeded = false;

    FileSystem srcFs = tableArchivePath.getFileSystem(conf);
    FileSystem desFs = FileSystem.get(conf);
    if (tableArchivePath.getName().startsWith("/")) {
      isCopyNeeded = true;
    } else {
      // This should match what is done in @see HRegionFileSystem#bulkLoadStoreFile(String, Path,
      // long)
      if (srcFs.getUri().equals(desFs.getUri())) {
        LOG.debug("cluster hold the backup image: " + srcFs.getUri() + "; local cluster node: "
            + desFs.getUri());
        isCopyNeeded = true;
      }
    }
    if (isCopyNeeded) {
      LOG.debug("File " + tableArchivePath + " on local cluster, back it up before restore");
      if (desFs.exists(restoreTmpPath)) {
        try {
          desFs.delete(restoreTmpPath, true);
        } catch (IOException e) {
          LOG.debug("Failed to delete path: " + restoreTmpPath
              + ", need to check whether restore target DFS cluster is healthy");
        }
      }
      FileUtil.copy(srcFs, tableArchivePath, desFs, restoreTmpPath, false, conf);
      LOG.debug("Copied to temporary path on local cluster: " + restoreTmpPath);
      tableArchivePath = restoreTmpPath;
    }
    return tableArchivePath;
  }

  private HTableDescriptor getTableDescriptor(FileSystem fileSys, TableName tableName,
      String lastIncrBackupId) throws IOException {
    if (lastIncrBackupId != null) {
      String target =
          BackupUtils.getTableBackupDir(backupRootPath.toString(),
            lastIncrBackupId, tableName);
      return FSTableDescriptors.getTableDescriptorFromFs(fileSys, new Path(target));
    }
    return null;
  }

  private void restoreTableAndCreate(Connection conn, TableName tableName, TableName newTableName,
      Path tableBackupPath, boolean truncateIfExists, String lastIncrBackupId) throws IOException {
    if (newTableName == null) {
      newTableName = tableName;
    }
    FileSystem fileSys = tableBackupPath.getFileSystem(this.conf);

    // get table descriptor first
    HTableDescriptor tableDescriptor = getTableDescriptor(fileSys, tableName, lastIncrBackupId);
    if (tableDescriptor != null) {
      LOG.debug("Retrieved descriptor: " + tableDescriptor + " thru " + lastIncrBackupId);
    }

    if (tableDescriptor == null) {
      Path tableSnapshotPath = getTableSnapshotPath(backupRootPath, tableName, backupId);
      if (fileSys.exists(tableSnapshotPath)) {
        // snapshot path exist means the backup path is in HDFS
        // check whether snapshot dir already recorded for target table
        if (snapshotMap.get(tableName) != null) {
          SnapshotDescription desc =
              SnapshotDescriptionUtils.readSnapshotInfo(fileSys, tableSnapshotPath);
          SnapshotManifest manifest = SnapshotManifest.open(conf, fileSys, tableSnapshotPath, desc);
          tableDescriptor = manifest.getTableDescriptor();
        } else {
          tableDescriptor = getTableDesc(tableName);
          snapshotMap.put(tableName, getTableInfoPath(tableName));
        }
        if (tableDescriptor == null) {
          LOG.debug("Found no table descriptor in the snapshot dir, previous schema would be lost");
        }
      } else {
        throw new IOException("Table snapshot directory: " +
            tableSnapshotPath + " does not exist.");
      }
    }

    Path tableArchivePath = getTableArchivePath(tableName);
    if (tableArchivePath == null) {
      if (tableDescriptor != null) {
        // find table descriptor but no archive dir means the table is empty, create table and exit
        if (LOG.isDebugEnabled()) {
          LOG.debug("find table descriptor but no archive dir for table " + tableName
              + ", will only create table");
        }
        tableDescriptor.setName(newTableName);
        checkAndCreateTable(conn, tableBackupPath, tableName, newTableName, null, tableDescriptor,
          truncateIfExists);
        return;
      } else {
        throw new IllegalStateException("Cannot restore hbase table because directory '"
            + " tableArchivePath is null.");
      }
    }

    if (tableDescriptor == null) {
      tableDescriptor = new HTableDescriptor(newTableName);
    } else {
      tableDescriptor.setName(newTableName);
    }

    // record all region dirs:
    // load all files in dir
    try {
      ArrayList<Path> regionPathList = getRegionList(tableName);

      // should only try to create the table with all region informations, so we could pre-split
      // the regions in fine grain
      checkAndCreateTable(conn, tableBackupPath, tableName, newTableName, regionPathList,
        tableDescriptor, truncateIfExists);
      if (tableArchivePath != null) {
        // start real restore through bulkload
        // if the backup target is on local cluster, special action needed
        Path tempTableArchivePath = checkLocalAndBackup(tableArchivePath);
        if (tempTableArchivePath.equals(tableArchivePath)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("TableArchivePath for bulkload using existPath: " + tableArchivePath);
          }
        } else {
          regionPathList = getRegionList(tempTableArchivePath); // point to the tempDir
          if (LOG.isDebugEnabled()) {
            LOG.debug("TableArchivePath for bulkload using tempPath: " + tempTableArchivePath);
          }
        }

        LoadIncrementalHFiles loader = createLoader(tempTableArchivePath, false);
        for (Path regionPath : regionPathList) {
          String regionName = regionPath.toString();
          if (LOG.isDebugEnabled()) {
            LOG.debug("Restoring HFiles from directory " + regionName);
          }
          String[] args = { regionName, newTableName.getNameAsString() };
          loader.run(args);
        }
      }
      // we do not recovered edits
    } catch (Exception e) {
      throw new IllegalStateException("Cannot restore hbase table", e);
    }
  }

  /**
   * Gets region list
   * @param tableArchivePath table archive path
   * @return RegionList region list
   * @throws FileNotFoundException exception
   * @throws IOException exception
   */
  ArrayList<Path> getRegionList(Path tableArchivePath) throws FileNotFoundException, IOException {
    ArrayList<Path> regionDirList = new ArrayList<Path>();
    FileStatus[] children = fs.listStatus(tableArchivePath);
    for (FileStatus childStatus : children) {
      // here child refer to each region(Name)
      Path child = childStatus.getPath();
      regionDirList.add(child);
    }
    return regionDirList;
  }

  /**
   * Create a {@link LoadIncrementalHFiles} instance to be used to restore the HFiles of a full
   * backup.
   * @return the {@link LoadIncrementalHFiles} instance
   * @throws IOException exception
   */
  private LoadIncrementalHFiles createLoader(Path tableArchivePath, boolean multipleTables)
      throws IOException {

    // By default, it is 32 and loader will fail if # of files in any region exceed this
    // limit. Bad for snapshot restore.
    this.conf.setInt(LoadIncrementalHFiles.MAX_FILES_PER_REGION_PER_FAMILY, Integer.MAX_VALUE);
    this.conf.set(LoadIncrementalHFiles.IGNORE_UNMATCHED_CF_CONF_KEY, "yes");
    LoadIncrementalHFiles loader = null;
    try {
      loader = new LoadIncrementalHFiles(this.conf);
    } catch (Exception e1) {
      throw new IOException(e1);
    }
    return loader;
  }

  /**
   * Calculate region boundaries and add all the column families to the table descriptor
   * @param regionDirList region dir list
   * @return a set of keys to store the boundaries
   */
  byte[][] generateBoundaryKeys(ArrayList<Path> regionDirList) throws FileNotFoundException,
      IOException {
    TreeMap<byte[], Integer> map = new TreeMap<byte[], Integer>(Bytes.BYTES_COMPARATOR);
    // Build a set of keys to store the boundaries
    // calculate region boundaries and add all the column families to the table descriptor
    for (Path regionDir : regionDirList) {
      LOG.debug("Parsing region dir: " + regionDir);
      Path hfofDir = regionDir;

      if (!fs.exists(hfofDir)) {
        LOG.warn("HFileOutputFormat dir " + hfofDir + " not found");
      }

      FileStatus[] familyDirStatuses = fs.listStatus(hfofDir);
      if (familyDirStatuses == null) {
        throw new IOException("No families found in " + hfofDir);
      }

      for (FileStatus stat : familyDirStatuses) {
        if (!stat.isDirectory()) {
          LOG.warn("Skipping non-directory " + stat.getPath());
          continue;
        }
        boolean isIgnore = false;
        String pathName = stat.getPath().getName();
        for (String ignore : ignoreDirs) {
          if (pathName.contains(ignore)) {
            LOG.warn("Skipping non-family directory" + pathName);
            isIgnore = true;
            break;
          }
        }
        if (isIgnore) {
          continue;
        }
        Path familyDir = stat.getPath();
        LOG.debug("Parsing family dir [" + familyDir.toString() + " in region [" + regionDir + "]");
        // Skip _logs, etc
        if (familyDir.getName().startsWith("_") || familyDir.getName().startsWith(".")) {
          continue;
        }

        // start to parse hfile inside one family dir
        Path[] hfiles = FileUtil.stat2Paths(fs.listStatus(familyDir));
        for (Path hfile : hfiles) {
          if (hfile.getName().startsWith("_") || hfile.getName().startsWith(".")
              || StoreFileInfo.isReference(hfile.getName())
              || HFileLink.isHFileLink(hfile.getName())) {
            continue;
          }
          HFile.Reader reader = HFile.createReader(fs, hfile, conf);
          final byte[] first, last;
          try {
            reader.loadFileInfo();
            first = reader.getFirstRowKey();
            last = reader.getLastRowKey();
            LOG.debug("Trying to figure out region boundaries hfile=" + hfile + " first="
                + Bytes.toStringBinary(first) + " last=" + Bytes.toStringBinary(last));

            // To eventually infer start key-end key boundaries
            Integer value = map.containsKey(first) ? (Integer) map.get(first) : 0;
            map.put(first, value + 1);
            value = map.containsKey(last) ? (Integer) map.get(last) : 0;
            map.put(last, value - 1);
          } finally {
            reader.close();
          }
        }
      }
    }
    return LoadIncrementalHFiles.inferBoundaries(map);
  }

  /**
   * Prepare the table for bulkload, most codes copied from
   * {@link LoadIncrementalHFiles#createTable(String, String)}
   * @param conn connection
   * @param tableBackupPath path
   * @param tableName table name
   * @param targetTableName target table name
   * @param regionDirList region directory list
   * @param htd table descriptor
   * @param truncateIfExists truncates table if exists
   * @throws IOException exception
   */
  private void checkAndCreateTable(Connection conn, Path tableBackupPath, TableName tableName,
      TableName targetTableName, ArrayList<Path> regionDirList, HTableDescriptor htd,
      boolean truncateIfExists) throws IOException {
    try (Admin admin = conn.getAdmin();) {
      boolean createNew = false;
      if (admin.tableExists(targetTableName)) {
        if (truncateIfExists) {
          LOG.info("Truncating exising target table '" + targetTableName
              + "', preserving region splits");
          admin.disableTable(targetTableName);
          admin.truncateTable(targetTableName, true);
        } else {
          LOG.info("Using exising target table '" + targetTableName + "'");
        }
      } else {
        createNew = true;
      }
      if (createNew) {
        LOG.info("Creating target table '" + targetTableName + "'");
        byte[][] keys = null;
        if (regionDirList == null || regionDirList.size() == 0) {
          admin.createTable(htd, null);
        } else {
          keys = generateBoundaryKeys(regionDirList);
          // create table using table descriptor and region boundaries
          admin.createTable(htd, keys);
        }
        long startTime = EnvironmentEdgeManager.currentTime();
        while (!admin.isTableAvailable(targetTableName, keys)) {
          try {
            Thread.sleep(100);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          if (EnvironmentEdgeManager.currentTime() - startTime > TABLE_AVAILABILITY_WAIT_TIME) {
            throw new IOException("Time out " + TABLE_AVAILABILITY_WAIT_TIME + "ms expired, table "
                + targetTableName + " is still not available");
          }
        }
      }
    }
  }

}
