package org.apache.trevni.avro.update;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericData.Record;
import org.apache.trevni.ValueType;
import org.apache.trevni.avro.update.BloomFilter.BloomFilterBuilder;
import org.apache.trevni.avro.update.CachList.FlagData;

public class NestManager {
    private NestSchema[] schemas;
    private Schema[] keySchemas;
    private Schema[] nestKeySchemas;
    private int[][] keyFields;
    private String resultPath;
    private String tmpPath;
    private int level;
    private int free;
    private int mul;

    private OffsetTree[] offsetTree;
    private BackTree[] backTree;
    private ForwardTree[] forwardTree;

    private CachList cach;

    private BloomFilter[] filter;

    private ColumnReader<Record> reader;

    private int tmpMerge;
    private String tmpPid;

    public NestManager(NestSchema[] schemas, String tmppath, String resultPath, int free, int mul) throws IOException {
        assert (schemas.length > 1);
        this.schemas = schemas;
        this.tmpPath = tmppath;
        this.resultPath = resultPath;
        level = schemas.length;
        this.free = free;
        this.mul = mul;
        keySchemas = new Schema[level];
        nestKeySchemas = new Schema[level];
        nestKeySchemas[level - 1] = keySchemas[level - 1] = setSchema(schemas[level - 1].getSchema(),
                schemas[level - 1].getKeyFields());
        for (int i = (level - 1); i > 0; i--) {
            keySchemas[i - 1] = setSchema(schemas[i - 1].getSchema(), schemas[i - 1].getKeyFields());
            nestKeySchemas[i - 1] = setSchema(keySchemas[i - 1], nestKeySchemas[i]);
        }
        create();
        tmpMerge = 0;
        tmpPid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.out.println("PID: " + tmpPid);
    }

    public void create() throws IOException {
        File rf = new File(resultPath + "result.trv");
        if (rf.exists()) {
            reader = new ColumnReader<Record>(rf);
        }
        offsetTree = new OffsetTree[level];
        backTree = new BackTree[level - 1];
        forwardTree = new ForwardTree[level - 1];
        filter = new BloomFilter[level];
        //        int[][] lff = new int[level][];
        //        for (int i = 0; i < level; i++) {
        //            lff[i] = schemas[i].getKeyFields();
        //        }
        keyFields = new int[level][];
        for (int j = 0; j < level; j++)
            keyFields[j] = schemas[j].getKeyFields();
        cach = new CachList(keyFields);
        new File(resultPath).mkdirs();

        for (int i = level - 1; i > 0; i--) {
            filter[i] = new BloomFilter(schemas[i].getBloomFile(), schemas[i].getSchema(), schemas[i].getKeyFields());
            offsetTree[i] = new OffsetTree(schemas[i].getKeyFields(), keySchemas[i],
                    resultPath + "offsetTree/" + schemas[i].getSchema().getName());
            schemas[i - 1].setNestedSchema(setSchema(schemas[i - 1].getSchema(), schemas[i].getNestedSchema()));
            backTree[level - i - 1] = new BackTree(schemas[i].getKeyFields(), schemas[i].getOutKeyFields(),
                    schemas[i].getSchema(), resultPath + "backTree/" + schemas[i].getSchema().getName());
        }
        filter[0] = new BloomFilter(schemas[0].getBloomFile(), schemas[0].getSchema(), schemas[0].getKeyFields());
        offsetTree[0] = new OffsetTree(schemas[0].getKeyFields(), keySchemas[0],
                resultPath + "offsetTree/" + schemas[0].getSchema().getName());

        for (int i = 0; i < forwardTree.length; i++)
            forwardTree[i] = new ForwardTree(keyFields[i], keyFields[i + 1], keySchemas[i], keySchemas[i + 1],
                    (resultPath + "forwardTree/" + schemas[i].getSchema().getName()));
    }

    public void close() throws IOException {
        if (!cach.isEmpty())
            merge();
        reader.close();
        for (ForwardTree f : forwardTree)
            f.close();
        for (BackTree b : backTree)
            b.close();
        for (OffsetTree o : offsetTree)
            o.close();
        for (BloomFilter bb : filter)
            bb.close();
    }

    public void openTree() {
        for (OffsetTree o : offsetTree)
            o.create();
        for (BackTree b : backTree)
            b.create();
        for (ForwardTree f : forwardTree)
            f.create();
    }

    public boolean exists(Record r) {
        int le = getLevel(r);
        return (offsetTree[le].get(new CombKey(r, keyFields[le])) != null);
    }

    public Integer getOffset(Record r) {
        int le = getLevel(r);
        return offsetTree[le].get(new CombKey(r, keyFields[le]));
    }

    public CombKey getUpperKey(Record r) {
        int le = getLevel(r);
        return backTree[level - le - 1].get(new CombKey(r, keyFields[le]));
    }

    public List<Record> getForwardKey(Record r) {
        int le = getLevel(r);
        if (le < level)
            return forwardTree[le].findDiskRecord(new KeyofBTree(new CombKey(r, keyFields[le])));
        else
            return null;
    }

    public void setMax(int max) {
        cach.setMAX(max);
    }

    private Schema setSchema(Schema schema, int[] fields) {
        List<Field> fs = schema.getFields();
        List<Field> keyFields = new ArrayList<Field>();
        for (int m : fields) {
            Field f = fs.get(m);
            keyFields.add(new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal()));
        }
        return Schema.createRecord(schema.getName(), schema.getDoc(), schema.getNamespace(), false, keyFields);
    }

    private Schema setSchema(Schema s1, Schema s2) {
        List<Field> fs = s1.getFields();
        List<Field> newFS = new ArrayList<Field>();
        for (Field f : fs) {
            newFS.add(new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal()));
        }
        newFS.add(new Schema.Field(s2.getName() + "Arr", Schema.createArray(s2), null, null));
        return Schema.createRecord(s1.getName(), s1.getDoc(), s1.getNamespace(), false, newFS);
    }

    public BloomFilterBuilder createBloom(int numElements, int index) throws IOException {
        BloomFilterModel model = BloomCount.computeBloomModel(BloomCount.maxBucketPerElement(numElements), 0.01);
        return filter[index].creatBuilder(numElements, model.getNumHashes(), model.getNumBucketsPerElement());
    }

    public void load() throws IOException {
        assert level > 1;
        if (level == 2) {
            dLoad(schemas[1], schemas[0]); //write directly with column-store according to the highest primary key order
        } else {
            prLoad(schemas[level - 1], schemas[level - 2]); //read two primary files, write with row-store according to the next nested key order
            for (int i = level - 2; i > 1; i++) {
                orLoad(schemas[i], schemas[i - 1], i); //read a primary file and a row-store file, write with row store according to the next nested key order
            }
            laLoad(schemas[1], schemas[0]); //read a primary file and a row-store file, write with column-store according to the highest primary key order
        }
        openTree();
    }

    public int getLevel(Record r) {
        String name = r.getSchema().getName();
        int le = 0;
        while (le < level) {
            if (name.equals(schemas[le].getSchema().getName())) {
                break;
            }
            le++;
        }
        return le;
    }

    public Record search(Record key, Schema valueSchema) throws IOException {
        return search(key, valueSchema, true);
    }

    public void newMap(int merge) {
        try {
            Runtime.getRuntime().exec("jmap -dump:live,file=" + tmpPath + "map/" + merge + ".map " + tmpPid);
        } catch (Throwable e) {
        }
    }

    public int getTmpMerge() {
        return tmpMerge;
    }

    public void merge() throws IOException {
        if (cach.size() == 0)
            return;
        tmpMerge++;
        System.out.println();
        System.out.println("####################" + tmpMerge);
        long start = System.currentTimeMillis();
        cach.mergeCreate();
        int[] number = new int[level];
        for (int i = 0; i < level; i++) {
            while (cach.hasNext(i)) {
                Entry<CombKey, FlagData> ne = cach.next(i);
                if (ne.getValue().getFlag() == (byte) 4) {
                    int nest = 1;
                    int place = (int) offsetTree[i].get(ne.getKey());
                    Record r = reader.search(schemas[i].getSchema(), place); //read the record from disk to complement the update-insert(5) record
                    Record exF = cach.extraFind(r, i, false).getData();
                    for (int k = 0; k < r.getSchema().getFields().size(); k++) {
                        Object o = exF.get(k);
                        if (o != null)
                            r.put(k, o);
                    }
                    cach.addToMergeList(place, null, i);
                    for (int j = i; j < (level - 1); j++) {
                        int p = 0;
                        p += reader.searchArray(0, j, place);
                        place = p;
                        p = 0;
                        for (int m = 0; m < nest; m++) {
                            p += reader.nextArray(j);
                        }
                        nest = p;
                        for (int n = place; n < (place + nest); n++) {
                            cach.addToMergeList(n, null, j);
                        }
                        Record[] records = reader.search(schemas[j].getSchema(), place, nest);
                        for (Record record : records) {
                            FlagData fd = cach.find(record, j, false);
                            if (fd == null)
                                cachOperate(record, (byte) 1, j);
                            else if (fd.getFlag() == (byte) 3) {
                                Record tmp = fd.getData();
                                int ll = tmp.getSchema().getFields().size();
                                for (int mm = 0; mm < ll; mm++) {
                                    if (tmp.get(mm) != null)
                                        record.put(mm, tmp.get(mm));
                                }
                                cachOperate(record, (byte) 1, j);
                            } else {
                                cach.delete(record, j, false);
                                if (fd.getFlag() == (byte) 4) {
                                    FlagData ff = cach.extraFind(record, j, false);
                                    cach.extraDelete(record, j, false);
                                    int len = record.getSchema().getFields().size();
                                    for (int k = 0; k < len; k++) {
                                        Object o = ff.getData().get(k);
                                        if (o != null)
                                            record.put(k, o);
                                    }
                                    cachOperate(record, (byte) 1, j);
                                }
                            }
                        }
                    }
                } else if (ne.getValue().getFlag() == (byte) 2) {
                    number[i]--;
                    cach.addToMergeList(offsetTree[i].get(ne.getKey()).intValue(), null, i); //find the place in in disk, and add to mergeList with null value(means delete)
                } else {
                    if (ne.getValue().getFlag() == (byte) 1)
                        number[i]++;
                    NestCombKey fk = findKey(ne.getValue().getData(), i);
                    cach.addToSortList(fk, ne.getValue(), i);
                }
            }
            //            cach.clear(i);
            if (i > 0) {
                while (cach.extraHasNext(i)) {
                    Entry<CombKey, FlagData> ne = cach.extraNext(i);
                    NestCombKey fk = findKey(ne.getValue().getData(), i);
                    cach.addToSortList(fk, ne.getValue(), i);
                }
                //                cach.extraClear(i);
            }
            cach.sortSortList(i);
            cach.sortMergeList(i);
        }
        cach.hashClear();
        long t1 = System.currentTimeMillis();
        System.out.println("hash -> sortList(mergeList): " + (t1 - start));
        sortToMerge();
        long t2 = System.currentTimeMillis();
        System.out.println("sortList -> mergeList: " + (t2 - t1));
        updateBtree(number);
        long t3 = System.currentTimeMillis();
        System.out.println("update Btree and bloom filter: " + (t3 - t2));
        mergeWrite();
        long end = System.currentTimeMillis();
        System.out.println("merge write: " + (end - t3));
        System.out.println("sum time: " + (end - start));
        openTree();
        //        newMap(tmpMerge);
    }

    public static void shDelete(String path) {
        try {
            Process proc = Runtime.getRuntime().exec("rm " + path);
            StreamGobbler errorGobbler = new StreamGobbler(proc.getErrorStream(), "Error");
            StreamGobbler outputGobbler = new StreamGobbler(proc.getInputStream(), "Output");
            errorGobbler.start();
            outputGobbler.start();
            proc.waitFor();
        } catch (IOException x) {
            x.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void arrayTest() throws IOException {
        reader.create();
        int okLen = 0;
        int lkLen = 0;
        int len1 = reader.getRowCount(0);
        int len2 = reader.getRowCount(9);
        int len3 = reader.getRowCount(19);

        int i = 0;
        int aL1 = reader.getRowCount(8);
        System.out.println(aL1);
        while (i < aL1) {
            okLen += reader.nextArray(0);
            i++;
        }
        i = 0;
        int aL2 = reader.getRowCount(18);
        System.out.println(aL2);
        while (i < aL2) {
            lkLen += reader.nextArray(1);
            i++;
        }
        System.out.println(len1);
        System.out.println(len2 + " VS " + okLen);
        System.out.println(len3 + " VS " + lkLen);
    }

    public void sortToMerge() throws IOException {
        cach.createSortIterator();
        Integer[] place = new Integer[level];
        for (int i = 0; i < level; i++)
            place[i] = new Integer(0);
        Entry<NestCombKey, FlagData>[] sort = new Entry[level];
        for (int i = 0; i < level; i++) {
            if (cach.hasNextSort(i))
                sort[i] = cach.nextSort(i);
        }
        reader.create();
        reader.createSchema(nestKeySchemas[0]);
        //        int len = reader.getRowCount(0);
        //        int index = 0;

        Integer[] mergeIndex = new Integer[level];
        for (int i = 0; i < level; i++)
            mergeIndex[i] = new Integer(0);
        int[] mergeLen = new int[level];
        for (int i = 0; i < mergeLen.length; i++) {
            mergeLen[i] = cach.getMergeLength(i);
        }

        Integer[] count = new Integer[level - 1];
        RandomAccessFile[] arrayColumns = new RandomAccessFile[level - 1];
        for (int i = 0; i < level - 1; i++) {
            count[i] = new Integer(0);
            arrayColumns[i] = new RandomAccessFile((tmpPath + "array" + i), "rw");
            arrayColumns[i].seek(4);
        }

        while (reader.hasNext()) {
            //            boolean skip = true;
            //            for (int i = 0; i < level; i++) {
            //                if (sort[i] != null) {
            //                    skip = false;
            //                    break;
            //                }
            //            }
            //            if (skip)
            //                break;
            Record nestKey = reader.next();
            //            index++;
            List<NestCombKey>[] keys = new List[level];
            for (int i = 0; i < level; i++) {
                keys[i] = new ArrayList<NestCombKey>();
            }
            keys[0].add(new NestCombKey(new CombKey(nestKey, keyFields[0].length)));
            comNestKey(nestKey.get(keyFields[0].length), 1, keys, keys[0].get(0));

            List<NestCombKey> kks = new ArrayList<NestCombKey>();
            while (sort[0] != null && sort[0].getKey().compareTo(keys[0].get(0)) < 0) {
                cach.addToMergeList(place[0].intValue(), sort[0].getValue(), 0);
                kks.add(sort[0].getKey());
                if (cach.hasNextSort(0))
                    sort[0] = cach.nextSort(0);
                else {
                    sort[0] = null;
                    cach.sortClear(0);
                }
            }
            if (sort[0] != null && sort[0].getKey().compareTo(keys[0].get(0)) == 0) {
                cach.addToMergeList(place[0].intValue(), sort[0].getValue(), 0);
                kks.add(sort[0].getKey());
                if (cach.hasNextSort(0))
                    sort[0] = cach.nextSort(0);
                else {
                    sort[0] = null;
                    cach.sortClear(0);
                }
            } else if (mergeIndex[0].intValue() < mergeLen[0]
                    && cach.getMergePlace(0, mergeIndex[0]) == place[0].intValue()) {
                mergeIndex[0] = mergeIndex[0].intValue() + 1;
            } else {
                kks.add(keys[0].get(0));
            }
            place[0] = place[0].intValue() + 1;
            if (kks.isEmpty())
                sortToMerge(keys, 1, count, place, mergeIndex, mergeLen);
            else
                sortToMerge(sort, keys, 1, arrayColumns, count, kks, place, mergeIndex, mergeLen);
        }
        while (sort[0] != null) {
            List<NestCombKey> kks = new ArrayList<NestCombKey>();
            cach.addToMergeList(place[0].intValue(), sort[0].getValue(), 0);
            kks.add(sort[0].getKey());
            if (cach.hasNextSort(0))
                sort[0] = cach.nextSort(0);
            else {
                sort[0] = null;
                cach.sortClear(0);
            }
            sortToMerge(sort, 1, arrayColumns, count, kks, place);
            cach.sortClear(0);
        }
        for (int i = 0; i < level; i++)
            cach.sortMergeList(i);
        //        for (int i = 0; i < level; i++) {
        //            if (sort[i] != null) {
        //                cach.addToMergeList(place[i], sort[i].getValue(), i);
        //                while (cach.hasNextSort(i))
        //                    cach.addToMergeList(place[i], cach.nextSort(i).getValue(), i);
        //                cach.sortClear(i);
        //            }
        //            cach.sortMergeList(i);
        //        }
        cach.sortClear();
        for (int i = 0; i < arrayColumns.length; i++) {
            arrayColumns[i].seek(0);
            arrayColumns[i].writeInt(count[i]);
            arrayColumns[i].close();
        }
    }

    private void sortToMerge(Entry<NestCombKey, FlagData>[] sort, int le, RandomAccessFile[] arrayColumns,
            Integer[] count, List<NestCombKey> kks, Integer[] place) throws IOException {
        assert (le < level);
        int[] cc = new int[kks.size()];
        int kIn = 0;
        List<NestCombKey> kkks = new ArrayList<NestCombKey>();
        while (sort[le] != null && kks.get(kks.size() - 1).compareTo(sort[le].getKey(), le - 1) >= 0) {
            cach.addToMergeList(place[le].intValue(), sort[le].getValue(), le);
            kkks.add(sort[le].getKey());
            while (kks.get(kIn).compareTo(sort[le].getKey(), le - 1) < 0)
                kIn++;
            if (kks.get(kIn).compareTo(sort[le].getKey(), le - 1) == 0) {
                cc[kIn]++;
            } else {
                System.out.println("!!!!!!!!!!!error:" + sort[le].getKey().getKey(0).get()[0].toString());
            }
            if (cach.hasNextSort(le))
                sort[le] = cach.nextSort(le);
            else {
                sort[le] = null;
                cach.sortClear(le);
            }
        }
        for (int c : cc) {
            arrayColumns[le - 1].writeInt(c);
        }
        count[le - 1] = count[le - 1].intValue() + cc.length;
        if (le < (level - 1))
            sortToMerge(sort, (le + 1), arrayColumns, count, kkks, place);
    }

    private void sortToMerge(Entry<NestCombKey, FlagData>[] sort, List<NestCombKey>[] keys, int le,
            RandomAccessFile[] arrayColumns, Integer[] count, List<NestCombKey> kks, Integer[] place,
            Integer[] mergeIndex, int[] mergeLen) throws IOException {
        assert (le < level);
        int[] cc = new int[kks.size()];
        int kIn = 0;
        List<NestCombKey> kkks = new ArrayList<NestCombKey>();
        for (NestCombKey o : keys[le]) {
            while (sort[le] != null && sort[le].getKey().compareTo(o) < 0) {
                cach.addToMergeList(place[le].intValue(), sort[le].getValue(), le);
                kkks.add(sort[le].getKey());
                while (kks.get(kIn).compareTo(sort[le].getKey(), le) < 0)
                    kIn++;
                if (kks.get(kIn).compareTo(sort[le].getKey(), le) == 0) {
                    cc[kIn]++;
                } else {
                    System.out.println("!!!!!!!!!!!error:" + sort[le].getKey().getKey(0).get()[0].toString());
                }
                if (cach.hasNextSort(le))
                    sort[le] = cach.nextSort(le);
                else {
                    sort[le] = null;
                    cach.sortClear(le);
                }
            }
            if (sort[le] != null && sort[le].getKey().compareTo(o) == 0) {
                cach.addToMergeList(place[le].intValue(), sort[le].getValue(), le);
                kkks.add(sort[le].getKey());
                while (kks.get(kIn).compareTo(sort[le].getKey(), le) < 0)
                    kIn++;
                if (kks.get(kIn).compareTo(sort[le].getKey(), le) == 0) {
                    cc[kIn]++;
                } else {
                    System.out.println("!!!!!!!!!!!error:" + sort[le].getKey().getKey(0).get()[0].toString());
                }
                if (cach.hasNextSort(le))
                    sort[le] = cach.nextSort(le);
                else {
                    sort[le] = null;
                    cach.sortClear(le);
                }
            } else if (mergeIndex[le].intValue() < mergeLen[le]
                    && cach.getMergePlace(le, mergeIndex[le]) == place[le].intValue()) {
                mergeIndex[le] = mergeIndex[le].intValue() + 1;
            } else {
                kkks.add(o);
                while (kks.get(kIn).compareTo(o, le) < 0)
                    kIn++;
                if (kks.get(kIn).compareTo(o, le) == 0) {
                    cc[kIn]++;
                } else {
                    System.out.println("!!!!!!!!!!!error:" + o.getKey(0).get()[0].toString());
                }
            }
            //            int p = offsetTree[le].get(o.getKey(le)).intValue();
            //            if (place[le].intValue() != p) {
            //                System.out.println();
            //                System.out.println("key0: " + o.getKey(0).get(0) + "key" + le + ": " + o.getKey(le).get());
            //                System.out.println("place: " + place[le] + "\toffset: " + p);
            //            }
            place[le] = place[le].intValue() + 1;
        }

        while (sort[le] != null && sort[le].getKey().compareTo(kks.get(kks.size() - 1), le) <= 0) {
            cach.addToMergeList(place[le].intValue(), sort[le].getValue(), le);
            kkks.add(sort[le].getKey());
            while (kks.get(kIn).compareTo(sort[le].getKey(), le) < 0)
                kIn++;
            if (kks.get(kIn).compareTo(sort[le].getKey(), le) == 0) {
                cc[kIn]++;
            } else {
                System.out.println("!!!!!!!!!!!error:" + sort[le].getKey().getKey(0).get()[0].toString());
            }
            if (cach.hasNextSort(le))
                sort[le] = cach.nextSort(le);
            else {
                sort[le] = null;
                cach.sortClear(le);
            }
        }

        if (!kks.isEmpty()) {
            for (int c : cc) {
                arrayColumns[le - 1].writeInt(c);
            }
            count[le - 1] = count[le - 1].intValue() + cc.length;
        }

        if (le < (level - 1)) {
            if (kkks.isEmpty())
                sortToMerge(keys, (le + 1), count, place, mergeIndex, mergeLen);
            else
                sortToMerge(sort, keys, (le + 1), arrayColumns, count, kkks, place, mergeIndex, mergeLen);
        }
    }

    private void sortToMerge(List<NestCombKey>[] keys, int le, Integer[] count, Integer[] place, Integer[] mergeIndex,
            int[] mergeLen) {
        assert (le < level);
        for (NestCombKey o : keys[le]) {
            if (mergeIndex[le].intValue() < mergeLen[le]
                    && cach.getMergePlace(le, mergeIndex[le]) == place[le].intValue()) {
                mergeIndex[le] = mergeIndex[le].intValue() + 1;
            } else {
                System.out.println("!!!!delete error: no that delete:" + o.getKey(0).get()[0].toString());
            }
            place[le] = place[le].intValue() + 1;
        }
        if (le < (level - 1)) {
            sortToMerge(keys, (le + 1), count, place, mergeIndex, mergeLen);
        }
    }

    public void updateBtree(int[] number) throws IOException {
        int[] numElements = new int[level];
        BloomFilterBuilder[] builder = new BloomFilterBuilder[level];
        for (int i = 0; i < level; i++) {
            if (!filter[i].isActivated())
                filter[i].activate();
            numElements[i] = filter[i].getNumElements() + number[i];
            filter[i].cover();
            builder[i] = createBloom(numElements[i], i);
        }

        forwardTree[0].createMerge((int) (numElements[0] / 500));
        backTree[level - 2].createMerge((int) (numElements[1] / 500));
        Entry<CombKey, List<Record>> en = forwardTree[0].nextMerge();
        if (backTree[level - 2].isbtree()) {
            while (en != null) {
                builder[0].add(en.getKey());
                for (Record r : en.getValue()) {
                    backTree[level - 2].put(new CombKey(r), en.getKey());
                    builder[1].add(new CombKey(r));
                }
                en = forwardTree[0].nextMerge();
            }
        } else {
            while (en != null) {
                builder[0].add(en.getKey());
                for (Record r : en.getValue()) {
                    builder[1].add(new CombKey(r));
                }
                en = forwardTree[0].nextMerge();
            }
        }
        for (int i = 1; i < (level - 1); i++) {
            forwardTree[i].createMerge((int) (numElements[i] / 500));
            backTree[level - i - 2].createMerge((int) (numElements[i + 1] / 500));
            en = forwardTree[i].nextMerge();
            if (backTree[level - i - 2].isbtree()) {
                while (en != null) {
                    for (Record r : en.getValue()) {
                        backTree[level - i - 2].put(new CombKey(r), en.getKey());
                        builder[i + 1].add(new CombKey(r));
                    }
                    en = forwardTree[i].nextMerge();
                }
            } else {
                while (en != null) {
                    for (Record r : en.getValue()) {
                        builder[i + 1].add(new CombKey(r));
                    }
                    en = forwardTree[i].nextMerge();
                }
            }
        }

        for (BackTree bb : backTree) {
            bb.write();
        }
        for (BloomFilterBuilder b : builder) {
            b.write();
        }
        //        forwardTree[0].createMerge((int) (numElements[0] / 500));
        //        for (int i = 0; i < backTree.length; i++) {
        //            backTree[i].createMerge((int) (numElements[level - i - 1] / 500));
        //        }
        for (int i = 0; i < offsetTree.length; i++) {
            offsetTree[i].createMerge((int) (numElements[i] / 500));
        }
        //        RandomAccessFile[] arrayColumns = new RandomAccessFile[level - 1];
        //        Integer[] count = new Integer[level - 1];
        //        for (int i = 0; i < level - 1; i++) {
        //            count[i] = new Integer(0);
        //            arrayColumns[i] = new RandomAccessFile((tmpPath + "array" + i), "rw");
        //            arrayColumns[i].seek(4);
        //        }
        //        Integer[] place = new Integer[level];
        //        for (int i = 0; i < level; i++)
        //            place[i] = new Integer(0);
        //        Entry<CombKey, List<Record>> en = forwardTree[0].nextMerge();
        //        while (en != null) {
        //            if (en.getValue() != null) {
        //                offsetTree[0].put(en.getKey(), place[0].intValue());
        //                builder[0].add(en.getKey());
        //                place[0] = place[0].intValue() + 1;
        //                updateTree(en.getKey(), en.getValue(), 1, place, arrayColumns, count, builder);
        //            }
        //            en = forwardTree[0].nextMerge();
        //        }
        //        for (int i = 0; i < (level - 1); i++) {
        //            arrayColumns[i].seek(0);
        //            arrayColumns[i].writeInt(count[i].intValue());
        //            arrayColumns[i].close();
        //        }
    }

    public void updateTree(CombKey upperKey, List<Record> nests, int le, Integer[] place,
            RandomAccessFile[] arrayColumns, Integer[] count, BloomFilterBuilder[] builder) throws IOException {
        assert (le < level);
        //        .write(getBytes(nests.size()), index[le - 1].intValue(), 4);
        //        index[le - 1] = index[le - 1].intValue() + 4;
        count[le - 1] = count[le - 1].intValue() + 1;
        if (!nests.isEmpty()) {
            arrayColumns[le - 1].writeInt(nests.size());
            for (Record key : nests) {
                backTree[level - le - 1].put(new CombKey(key), upperKey);
                offsetTree[le].put(key, place[le].intValue(), true);
                builder[le].add(key, true);
                place[le] = place[le].intValue() + 1;
                if (le < (level - 1)) {
                    List<Record> rs = forwardTree[le].find(new CombKey(key));
                    updateTree(new CombKey(key), rs, (le + 1), place, arrayColumns, count, builder);
                }
            }
        } else
            arrayColumns[le - 1].writeInt(0);
    }

    public void mergeWrite() throws IOException {
        reader.create();
        ValueType[] types = reader.getTypes();
        AvroColumnWriter writer = new AvroColumnWriter(schemas[0].getNestedSchema(), tmpPath + "result.tmp");
        cach.mergeWriteCreate();
        int i = 0;
        int a = 0;
        int array = 0;
        for (ValueType type : types) {
            //            System.out.println("%%%column: " + i);
            if (type == ValueType.NULL) {
                RandomAccessFile in = new RandomAccessFile((tmpPath + "array" + a), "rw");
                int len = in.readInt();
                //                System.out.println("         rowcount: " + len);
                for (int k = 0; k < len; k++) {
                    writer.writeArrayColumn(i, in.readInt());
                }
                writer.flush(i);
                in.close();
                in = null;
                //                new File(tmpPath + "array" + a).delete();
                shDelete(tmpPath + "array" + a);
                a++;
                i++;
                array = i;
            } else {
                int len = reader.getRowCount(i);
                //                System.out.println("         rowcount: " + len);
                Entry<Integer, FlagData> en = cach.mergeNext(a);
                int k = 0;
                while (en != null && k < len) {
                    while (k < en.getKey()) {
                        writer.writeColumn(i, reader.nextValue(i));
                        k++;
                        //                        System.out.println(k);
                    }
                    if (en.getValue() == null) {
                        reader.nextValue(i);
                        k++;
                        //                        System.out.println(k);
                        en = cach.mergeNext(a);
                    } else {
                        byte f = en.getValue().getFlag();
                        if (f == (byte) 3) {
                            Object v = reader.nextValue(i);
                            k++;
                            //                            System.out.println(k);
                            Object up = en.getValue().getData().get(i - array);
                            if (up != null)
                                v = up;
                            writer.writeColumn(i, v);
                            en = cach.mergeNext(a);
                            //                            while (en != null && en.getValue() != null) {
                            //                                writer.writeColumn(i, en.getValue().getData().get(i - array));
                            //                                en = cach.mergeNext(a);
                            //                            }
                        } else {
                            writer.writeColumn(i, en.getValue().getData().get(i - array));
                            en = cach.mergeNext(a);
                            //                            while (en != null && en.getValue() != null) {
                            //                                writer.writeColumn(i, en.getValue().getData().get(i - array));
                            //                                en = cach.mergeNext(a);
                            //                            }
                            //                            if (k < len) {
                            //                                writer.writeColumn(i, reader.nextValue(i));
                            //                                k++;
                            //                            }
                        }
                    }
                }
                while (en != null) {
                    writer.writeColumn(i, en.getValue().getData().get(i - array));
                    en = cach.mergeNext(a);
                }
                while (k < len) {
                    writer.writeColumn(i, reader.nextValue(i));
                    k++;
                    //                    System.out.println(k);
                }
                writer.flush(i);
                cach.mergeWriteCreate();
                i++;
            }
        }
        cach.clear();
        reader.close();
        writer.close();
        reader = null;
        writer = null;
        //        new File(resultPath + "result.trv").delete();
        //        new File(resultPath + "result.head").delete();
        shDelete(resultPath + "result.trv");
        shDelete(resultPath + "result.head");
        new File(tmpPath + "result.tmp").renameTo(new File(resultPath + "result.trv"));
        new File(tmpPath + "result.head").renameTo(new File(resultPath + "result.head"));
        System.gc();
        reader = new ColumnReader<Record>(new File(resultPath + "result.trv"));
        long t1 = System.currentTimeMillis();
        for (int j = 0; j < level; j++) {
            int index = 0;
            reader.createSchema(keySchemas[j]);
            while (reader.hasNext()) {
                Record r = reader.next();
                offsetTree[j].put(new CombKey(r), index++);
            }
            offsetTree[j].write();
        }
        //        int[] index = new int[level];
        //        for (int m = 0; m < level; m++)
        //            index[m] = 0;
        //        Params param = new Params(new File(resultPath + "result.trv"));
        //        param.setSchema(nestKeySchemas[0]);
        //        InsertAvroColumnReader<Record> re = new InsertAvroColumnReader<Record>(param);
        //        while (re.hasNext()) {
        //            Record record = re.next();
        //            if (level > 1)
        //                //                forwardTree.put(record);
        //                offsetTree[0].put(record, index[0]++, true);
        //            List<Record> rs = (List<Record>) record.get(keyFields[0].length);
        //            for (int j = 1; j < level - 1; j++) {
        //                List<Record> tmp = new ArrayList<Record>();
        //                tmp.addAll(rs);
        //                rs.clear();
        //                for (int k = 0; k < tmp.size(); k++) {
        //                    Record r = tmp.get(k);
        //                    rs.addAll((List<Record>) r.get(keyFields[j].length));
        //                    offsetTree[j].put(r, index[j], true);
        //                    index[j]++;
        //                }
        //            }
        //            for (int k = 0; k < rs.size(); k++) {
        //                offsetTree[level - 1].put(rs.get(k), index[level - 1], true);
        //                index[level - 1]++;
        //            }
        //        }
        //        re.close();
        //        for (OffsetTree o : offsetTree)
        //            o.write();
        long t2 = System.currentTimeMillis();
        System.out.println("offsetTree update time:" + (t2 - t1));
    }

    /*
     * give a record in forwardTree,
     * according to this, compute the nestkeys in all levels with the recursion method
     */
    private void comNestKey(Object data, int le, List<NestCombKey>[] keys, NestCombKey upperkey) {
        assert (le < level);
        //        NestCombKey kk = (upperkey == null) ? new NestCombKey(new CombKey(data, schemas[le].getKeyFields().length))
        //                : new NestCombKey(upperkey, new CombKey(data, schemas[le].getKeyFields().length));
        //        keys[le].add(upperkey);
        if (data == null)
            return;
        List<Record> nest = (List<Record>) data;
        if (le < (level - 1)) {
            for (Record tm : nest) {
                CombKey key = new CombKey(tm, keyFields[le].length);
                keys[le].add(new NestCombKey(upperkey, key));
                comNestKey(tm.get(keyFields[le].length), le + 1, keys, new NestCombKey(upperkey, key));
            }
        } else {
            for (Record tm : nest) {
                CombKey key = new CombKey(tm, keyFields[le].length);
                keys[le].add(new NestCombKey(upperkey, key));
            }
        }
        //        if (le < level - 1) {
        //            List<Record> rs = (List<Record>) data.get(data.getSchema().getFields().size() - 1);
        //            for (Record dd : rs) {
        //                comNestKey(dd, (le + 1), keys, kk);
        //            }
        //        }
    }

    /*
     * first use backTree find the up level key,
     * then find the sortList in cach with binary search,
     * if exists return the nest combkey, else use backTree find the up level key until the nest combkey is found.
     * this return is not always the newest nest combkey.
     */
    private NestCombKey findSortKey(CombKey key, int le) {
        if (le == 0)
            return new NestCombKey(new CombKey[] { key });
        CombKey[] keys = new CombKey[le + 1];
        keys[le] = key;
        CombKey fk = backTree[level - le - 1].get(key);
        keys[le - 1] = fk;
        for (int i = le - 1; i > 0; i++) {
            NestCombKey fks = cach.findSort(keys[i], i);
            if (fks != null) {
                int j = 0;
                for (CombKey kk : fks.keys) {
                    keys[j++] = kk;
                }
                break;
            }
            keys[i - 1] = backTree[level - i - 1].get(keys[i]);
        }
        return new NestCombKey(keys);
    }

    /*
     * find the up comkkey with backTree,
     * return the disk nest combkey.
     */
    private NestCombKey findBackKey(CombKey key, int le) {
        if (le == 0)
            return new NestCombKey(new CombKey[] { key });
        CombKey[] keys = new CombKey[le + 1];
        keys[le] = key;
        for (int i = le; i > 0; i++) {
            keys[i - 1] = backTree[level - i - 1].get(keys[i]);
        }
        return new NestCombKey(keys);
    }

    /*
     * first use backTree find the up level key,
     * then find the up level cach's hash, find whether the foreign key is changed,
     * use the newer foreign key,
     * return the newest nest combkey.
     */
    private NestCombKey findKey(CombKey key, int le) {
        if (le == 0)
            return new NestCombKey(new CombKey[] { key });
        CombKey[] keys = new CombKey[le + 1];
        keys[le] = key;
        CombKey fk = backTree[level - le - 1].get(key);
        keys[le - 1] = fk;
        for (int i = le - 1; i > 0; i++) {
            FlagData fd = cach.find(keys[i], i);
            if (fd != null) {
                byte b = fd.getFlag();
                if (b == (byte) 1) {
                    keys[i - 1] = new CombKey(fd.getData(), schemas[i].getOutKeyFields());
                } else if (b == (byte) 4) {
                    fd = cach.extraFind(keys[i], i);
                    keys[i - 1] = new CombKey(fd.getData(), schemas[i].getOutKeyFields());
                }
                break;
            }
            keys[i - 1] = backTree[level - i - 1].get(keys[i]);
        }
        return new NestCombKey(keys);
    }

    /*
     * this record is a full data, return newest nest combkey.
     * compares to findKey(CombKey key, int le),
     * this function adds the operation that judge whether the record itself contains the foreignKey,
     * decides whether or not finding the backTree.
     */
    private NestCombKey findKey(Record data, int le) {
        if (le == 0)
            return new NestCombKey(new CombKey[] { new CombKey(data, schemas[0].getKeyFields()) });
        CombKey[] keys = new CombKey[le + 1];
        keys[le] = new CombKey(data, schemas[le].getKeyFields());
        keys[le - 1] = isNullKey(data, schemas[le].getOutKeyFields())
                ? findForeignKey(new CombKey(data, schemas[le].getKeyFields()), le)
                : new CombKey(data, schemas[le].getOutKeyFields());
        for (int i = le - 1; i > 0; i--) {
            FlagData fd = cach.find(keys[i], i);
            if (fd != null) {
                byte b = fd.getFlag();
                if (b == (byte) 1) {
                    keys[i - 1] = new CombKey(fd.getData(), schemas[i].getOutKeyFields());
                } else if (b == (byte) 4) {
                    fd = cach.extraFind(keys[i], i);
                    keys[i - 1] = new CombKey(fd.getData(), schemas[i].getOutKeyFields());
                }
                break;
            }
            keys[i - 1] = backTree[level - i - 1].get(keys[i]);
        }
        return new NestCombKey(keys);
    }

    private CombKey findForeignKey(CombKey key, int le) {
        assert (le > 0);
        FlagData fd = cach.find(key, le);
        if (fd != null) {
            byte b = fd.getFlag();
            if (b == (byte) 2)
                return null;
            if (b == (byte) 1)
                return new CombKey(fd.getData(), schemas[le].getOutKeyFields());
            if (b == (byte) 4)
                return new CombKey(cach.extraFind(key, le).getData(), schemas[le].getOutKeyFields());
            if (b == (byte) 3)
                if (!isNullKey(fd.getData(), schemas[le].getOutKeyFields()))
                    return new CombKey(fd.getData(), schemas[le].getOutKeyFields());
        }
        return backTree[level - le - 1].get(key);
    }

    public Record search(Record key, Schema valueSchema, boolean isKey) throws IOException {
        assert key.getSchema().getName().equals(valueSchema.getName());
        int le = getLevel(key);
        FlagData fd = cach.find(key, le, isKey);
        if (fd == null) {
            return diskSearch(key, valueSchema, isKey, le);
        } else {
            byte b = fd.getFlag();
            if (b == (byte) 1)
                return fd.getData();
            if (b == (byte) 2)
                return null;
            Record res = diskSearch(key, valueSchema, isKey, le);
            List<Field> fs = valueSchema.getFields();
            if (b == (byte) 4)
                fd = cach.extraFind(key, le, isKey);
            for (Field f : fs) {
                Object v = fd.getData().get(f.name());
                if (v != null)
                    res.put(f.name(), v);
            }
            return res;
        }
    }

    public Record diskSearch(Record key, Schema valueSchema, boolean isKey, int le) throws IOException {
        if (!filter[le].isActivated()) {
            filter[le].activate();
        }
        if (filter[le].contains(key, isKey, new long[2])) {
            Object v;
            if ((v = offsetTree[le].get(key, isKey)) != null) {
                if (reader == null) {
                    reader = new ColumnReader<Record>(new File(resultPath + "result.trv"));
                }
                int row = (Integer) v;
                Record res = reader.search(valueSchema, row);
                //System.out.println(res);
                return res;
            }
        }
        System.out.println("The key is not existed:" + key);
        return null;
    }

    public void insert(Record data) throws IOException {
        int le = getLevel(data);
        FlagData fd = cach.find(data, le, false);
        //    FlagData fd = cach.get(iii);
        //    byte b = fd.getFlag();
        if (fd == null) {
            if (!filter[le].isActivated()) {
                filter[le].activate();
            }
            if (filter[le].contains(data, false, new long[2])) {
                Object v;
                if ((v = offsetTree[le].get(data, false)) != null) {
                    //                    System.out.println("insert disk exists error: " + data);
                    return;
                } else {
                    if (insertLegal(data, le))
                        insertToCach(data, le);
                }
            } else {
                if (insertLegal(data, le))
                    insertToCach(data, le);
            }
        } else {
            if (fd.getFlag() == (byte) 2) {
                if (fd.getLevel() == le) {
                    deleteAndInsert(fd, data, le);
                } else {
                    System.out.println("insert illeagal: " + data);//insert illeagal
                    return;
                }
            } else {
                //                System.out.println("insert memory exists error: " + data);
                return;
            }
        }
        if (cach.isFull())
            merge();
    }

    public void upsert(Record data) throws IOException {
        int le = getLevel(data);
        FlagData fd = cach.find(data, le, false);
        if (fd == null) {
            if (!filter[le].isActivated()) {
                filter[le].activate();
            }
            if (filter[le].contains(data, false, new long[2])) {
                Object v;
                if ((v = offsetTree[le].get(data, false)) != null) {
                    updateToCach(data, le);
                } else {
                    if (insertLegal(data, le))
                        insertToCach(data, le);
                }
            } else {
                if (insertLegal(data, le))
                    insertToCach(data, le);
            }
        } else {
            if (fd.getFlag() == (byte) 2) {
                if (fd.getLevel() == le) {
                    deleteAndInsert(fd, data, le);
                } else {
                    System.out.println("insert illeagal: " + data);//insert illeagal
                    return;
                }
            } else {
                upsertAndUpdate(fd, data, le);
            }
        }
        if (cach.isFull())
            merge();
    }

    public void update(Record data) throws IOException {
        int le = getLevel(data);
        FlagData fd = cach.find(data, le, false);
        if (fd == null) {
            if (!filter[le].isActivated()) {
                filter[le].activate();
            }
            if (filter[le].contains(data, false, new long[2])) {
                Object v;
                if ((v = offsetTree[le].get(data, false)) != null) {
                    updateToCach(data, le);
                } else {
                    //                    System.out.println("update disk not exists error: " + data);
                    return;
                }
            } else {
                //                System.out.println("update disk not exists error: " + data);
                return;
            }
        } else {
            if (fd.getFlag() == (byte) 2) {
                //                System.out.println("update memory delete error: " + data);
                return;
            } else {
                upsertAndUpdate(fd, data, le);
            }
        }
        if (cach.isFull())
            merge();
    }

    public void delete(Record data) throws IOException {
        int le = getLevel(data);
        FlagData fd = cach.find(data, le, false);
        if (fd == null) {
            if (!filter[le].isActivated()) {
                filter[le].activate();
            }
            if (filter[le].contains(data, false, new long[2])) {
                if (offsetTree[le].get(data, false) != null) {
                    deleteToCach(data, le);
                } else {
                    //                    System.out.println("delete disk not exists error: " + data);
                    return;
                }
            } else {
                //                System.out.println("delete disk not exists error: " + data);
                return;
            }
        } else {
            if (fd.getFlag() == (byte) 2) {
                //                System.out.println("delete memory delete error: " + data);
                return;
            } else {
                upsertAndDelete(fd, data, le);
            }
        }
        if (cach.isFull())
            merge();
    }

    private void deleteAndInsert(FlagData fd, Record data, int le) throws IOException {
        cach.delete(data, le, false);
        //        if (le < (level - 1)) {
        //            for (Record[] re : split(fd.getData(), le)) {
        //                cachOperate(re[0], (byte) 2, re[1], (le - 1));
        //            }
        //        }
        updateToCach(data, le);
    }

    private boolean isNullKey(Record data, int[] fields) {
        for (int i = 0; i < fields.length; i++) {
            if (data.get(i) == null)
                return true;
        }
        return false;
    }

    private void upsertAndUpdate(FlagData fd, Record data, int le) throws IOException {
        byte b = fd.getFlag();
        if (b == (byte) 1) {
            Record d = fd.getData();
            for (int i = 0; i < schemas[le].getSchema().getFields().size(); i++) {
                if (data.get(i) == null)
                    data.put(i, d.get(i));
            }
            insertToCach(data, le);
        }
        if (b == (byte) 3) {
            updateToCach(data, le);
        }
        if (b == (byte) 4) {
            if (isNullKey(data, schemas[le].getOutKeyFields())) {
                Record rr = cach.extraFind(data, le, false).getData();
                for (int i = 0; i < rr.getSchema().getFields().size(); i++) {
                    if (data.get(i) != null) {
                        rr.put(i, data.get(i));
                    }
                }
                extraCachOperate(rr, (byte) 5, le);
                return;
            }
            //            Record former = findForwardInsertCache(data, le, false);
            //            deleteFromForwardInsert(former, le);
            CombKey uppNew = new CombKey(data, schemas[le].getOutKeyFields());
            Record rr = cach.extraFind(data, le, false).getData();
            CombKey upper = new CombKey(rr, schemas[le].getOutKeyFields());
            CombKey key = new CombKey(data, keyFields[le]);
            Record caR = fd.getData();
            CombKey uppDisk = new CombKey(caR, schemas[le].getOutKeyFields());
            //            for (int i = 0; i < le; i++) {
            //                caR = ((List<Record>) caR.get(caR.getSchema().getFields().size() - 1)).get(0);
            //            }
            if (uppNew.equals(uppDisk)) {
                //                cach.delete(data, le);
                //                deleteFromForwardInsert(former, le);
                forwardTree[le - 1].delete(uppDisk, key);
                forwardTree[le - 1].delete(upper, key);
                cach.extraDelete(data, le, false);
                cachOperate(data, (byte) 3, le);
                //                Record dd = new Record(nestKeySchemas[le]);
                //                setKey(dd, data, schemas[le].getKeyFields());
                //                if (le < (level - 1)) {
                //                    dd.put(nestKeySchemas[le].getFields().size() - 1, getAllNested(data, le));
                //                }
                //                addToForward(former, le);
                //                deleteFromForwardDelete(former, le);
                //                backTree[level - le - 1].put(data);
            } else {
                //                Record rr = cach.extraFind(data, le, false).getData();
                if (!uppNew.equals(upper)) {
                    forwardTree[le - 1].delete(upper, key);
                    forwardTree[le - 1].delete(uppNew, key);
                    //                    deleteFromForwardInsert(former, le);
                    //                    Record dd = new Record(nestKeySchemas[le]);
                    //                    setKey(dd, data, schemas[le].getKeyFields());
                    //                    if (le < (level - 1)) {
                    //                        dd.put(nestKeySchemas[le].getFields().size() - 1, getAllNested(data, le));
                    //                    }
                    //                    deleteFromForward(dd, le);
                    //                    backTree[level - le - 1].put(data);
                    //                    addToForward(former, le);
                }
                for (int i = 0; i < rr.getSchema().getFields().size(); i++) {
                    if (data.get(i) != null) {
                        rr.put(i, data.get(i));
                    }
                }
                //                for (int i = 0; i < le; i++) {
                //                    rr = ((List<Record>) rr.get(rr.getSchema().getFields().size() - 1)).get(0);
                //                }
                //                cach.extraDelete(rr, le);
                //                for (int i = 0; i < data.getSchema().getFields().size(); i++) {
                //                    rr.put(i, data.get(i));
                //                }
                extraCachOperate(rr, (byte) 5, le);
            }
        }
    }

    private void upsertAndDelete(FlagData fd, Record data, int le) throws IOException {
        //        cach.delete(data, le, true);
        byte b = fd.getFlag();
        if (b == (byte) 4) {
            cach.extraDelete(data, le, false);
        }
        deleteToCach(data, le);
        //        if (b == (byte) 1) {
        //            backTree[level - le - 1].remove(data, false);
        //        }
    }

    private List<Record[]> split(Record data, int le) {
        assert (le > 0);
        int i = data.getSchema().getFields().size();
        List<Record> rs = (List<Record>) data.get(i - 1);
        List<Record[]> res = new ArrayList<Record[]>();
        if (le == 1) {
            for (Record r : rs) {
                Record tm = new Record(data.getSchema());
                for (int k = 0; k < (i - 1); k++) {
                    tm.put(k, data.get(k));
                }
                tm.put((i - 1), new ArrayList<Record>().add(r));
                res.add(new Record[] { tm, r });
            }
        } else {
            List<Record[]> mm = split(rs.get(0), (le - 1));
            for (Record[] r : mm) {
                Record tm = new Record(data.getSchema());
                for (int k = 0; k < (i - 1); k++) {
                    tm.put(k, data.get(k));
                }
                tm.put((i - 1), new ArrayList<Record>().add(r[0]));
                res.add(new Record[] { tm, r[1] });
            }
        }
        return res;
    }

    private boolean insertLegal(Record data, int le) throws IOException {
        if (le == 0) {
            return true;
        }
        //    byte b = cach.find(data, schemas[le - 1].getOutKeyFields(), (le - 1)).getFlag();
        FlagData fd = cach.find(data, schemas[le].getOutKeyFields(), (le - 1));
        if (fd == null) {
            if (!filter[le - 1].isActivated()) {
                filter[le - 1].activate();
            }
            return (filter[le - 1].contains(data, schemas[le].getOutKeyFields(), new long[2])
                    && offsetTree[le - 1].get(data, schemas[le].getOutKeyFields()) != null);
        }
        return (fd.getFlag() != (byte) 2);
        //    boolean isKey = false;
        //    for(int i = (level - le - 1); i < (level - 1); i++){
        //      r = backTree[i].getRecord(r, isKey);
        //      isKey = true;
        //      if(r == null){
        //        System.out.println("Illeagal insert");
        //        return false;
        //      }
        //    }
    }

    //    private Record upperRecord(Record data, int le) {
    //        assert (le > 0);
    //        Record re;
    //        re = new Record(schemas[le - 1].getNestedSchema());
    //        setKey(re, schemas[le - 1].getKeyFields(), data, schemas[le].getOutKeyFields());
    //        for (int i = (le - 1); i > 0; i--) {
    //            Record key = backTree[level - i - 1].getRecord(re, false);
    //            assert (key != null);
    //            Record tm = new Record(schemas[i - 1].getNestedSchema());
    //            setKey(tm, schemas[i - 1].getKeyFields(), key, re);
    //            re = tm;
    //        }
    //        return re;
    //    }
    //
    //    private Record downRecord(Record data, int le) throws IOException {
    //        assert (le < level);
    //        Record upp = data;
    //        if (le > 0) {
    //            upp = upperRecord(data, le);
    //        }
    //        if (le == (level - 1)) {
    //            return upp;
    //        }
    //        Record fKey = forwardTree.getRecord(upp, false);
    //        for (int i = 0; i < le; i++) {
    //            List<Record> fk = (List<Record>) fKey.get(fKey.getSchema().getFields().size() - 1);
    //            upp = ((List<Record>) upp.get(upp.getSchema().getFields().size() - 1)).get(0);
    //            int[] fields = schemas[i + 1].getKeyFields();
    //            CombKey k = new CombKey(upp, fields);
    //            boolean find = false;
    //            for (int m = 0; m < fk.size(); m++) {
    //                if (k.equals(new CombKey(fk.get(m), comFields(fields.length)))) {
    //                    fKey = fk.get(m);
    //                    find = true;
    //                    break;
    //                }
    //            }
    //            if (!find) {
    //                //System.out.println("No key in disk");
    //                throw new IOException("No key in disk");
    //            }
    //        }
    //        List<Field> fs = new ArrayList<Field>();
    //        Schema ps = schemas[le].getSchema();
    //        for (Field f : ps.getFields()) {
    //            fs.add(new Schema.Field(f.name(), f.schema(), null, null));
    //        }
    //        List<Field> ff = fKey.getSchema().getFields();
    //        Field f = ff.get(ff.size() - 1);
    //        fs.add(new Schema.Field(f.name(), f.schema(), null, null));
    //        Schema vs = setSchema(schemas[le].getSchema(), keySchemas[le + 1]);
    //        Record re = new Record(vs);
    //        int len = vs.getFields().size();
    //        for (int i = 0; i < (len - 1); i++) {
    //            re.put(i, data.get(i));
    //        }
    //        re.put(len - 1, fKey.get(fKey.getSchema().getFields().size() - 1));
    //        re = upperRecord(re, le);
    //        return re;
    //    }

    private int[] comFields(int len) {
        int[] res = new int[len];
        for (int i = 0; i < len; i++) {
            res[i] = i;
        }
        return res;
    }

    private void setKey(Record to, int[] f1, Record from, int[] f2) {
        assert (f1.length == f2.length);
        for (int i = 0; i < f1.length; i++) {
            to.put(f1[i], from.get(f2[i]));
        }
        int i = to.getSchema().getFields().size();
        List<Record> arr = new ArrayList<Record>();
        arr.add(from);
        to.put((i - 1), arr);
    }

    private void setKey(Record to, Record from, int[] f2) {
        for (int i = 0; i < f2.length; i++) {
            to.put(i, from.get(f2[i]));
        }
    }

    private void setKey(Record to, int[] f1, Record from) {
        for (int i = 0; i < f1.length; i++) {
            to.put(f1[i], from.get(i));
        }
    }

    private void setKey(Record to, int[] f1, Record key, Record from) {
        int len = key.getSchema().getFields().size();
        assert (f1.length == len);
        for (int i = 0; i < len; i++) {
            to.put(f1[i], key.get(i));
        }
        int i = to.getSchema().getFields().size();
        List<Record> arr = new ArrayList<Record>();
        arr.add(from);
        to.put((i - 1), arr);
    }

    public void addToForward(Record data, int le, boolean isKey) {
        if (le < (level - 1))
            forwardTree[le].insert(data, isKey);
        if (le > 0) {
            CombKey key = isKey ? new CombKey(data, keyFields[le].length) : new CombKey(data, keyFields[le]);
            CombKey upper = findForeignKey(key, le);
            forwardTree[le - 1].insert(upper, key);
        }
        //        if (le > 0) {
        //            Record[] key = new Record[le + 1];
        //            List<Field> fs = data.getSchema().getFields();
        //            key[le] = new Record(nestKeySchemas[le]);
        //            int[] fields = schemas[le].getKeyFields();
        //            for (int i = 0; i < fields.length; i++) {
        //                key[le].put(i, data.get(fields[i]));
        //            }
        //            key[le] = data;
        //            //            key[le - 1] = backTree[level - le - 1].getRecord(key[le], true);
        //            for (int i = le; i > 0; i--) {
        //                Record fk = getForeignKey(key[i], i, true);
        //                key[i - 1] = new Record(nestKeySchemas[i - 1]);
        //                int len = keyFields[i - 1].length;
        //                for (int j = 0; j < len; j++)
        //                    key[i - 1].put(j, fk.get(j));
        //                key[i - 1].put(len, key[i]);
        //                key[i - 1] = getForeignKey(key[i], i, true);
        //            }
        //            Record former = forwardTree.find(key[0], true);
        //            int size = former.getSchema().getFields().size();
        //            former.put(size - 1, addForward((List<Record>) former.get(size - 1), key, le, 1));
        //            forwardTree.putCache(key, le, true);
        //        } else {
        //            forwardTree.putCache(data);
        //        }
    }

    public void deleteFromForward(Record data, int le) {

        //        if (le > 0) {
        //            Record[] key = new Record[le + 1];
        //            key[le] = new Record(nestKeySchemas[le]);
        //            int[] fields = schemas[le].getKeyFields();
        //            for (int i = 0; i < fields.length; i++) {
        //                key[le].put(i, data.get(fields[i]));
        //            }
        //            List<Field> fs = data.getSchema().getFields();
        //            key[le] = data;
        //            key[le - 1] = backTree[level - le - 1].getRecord(key[le], true);
        //            for (int i = le; i > 0; i--) {
        //                Record fk = getForeignKey(key[i], i, true);;
        //                key[i - 1] = new Record(nestKeySchemas[i - 1]);
        //                int len = keyFields[i - 1].length;
        //                for (int j = 0; j < len; j++)
        //                    key[i - 1].put(j, fk.get(j));
        //                key[i - 1].put(len, key[i]);
        //            }
        //            Record former = forwardTree.find(key[0], true);
        //            int size = former.getSchema().getFields().size();
        //            former.put(size - 1, deleteForward((List<Record>) former.get(size - 1), key, le, 1));
        //            forwardTree.deleteCache(key, le, true);
        //        } else {
        //            forwardTree.deleteCache(data, false);
        //        }
    }

    //    public void deleteFromForwardInsert(Record data, int le) {
    //        if (le > 0) {
    //            Record[] key = new Record[le + 1];
    //            key[le] = data;
    //            for (int i = le; i > 0; i--) {
    //                Record fk = getForeignKey(key[i], i, true);
    //                key[i - 1] = new Record(nestKeySchemas[i - 1]);
    //                int len = keyFields[i - 1].length;
    //                for (int j = 0; j < len; j++)
    //                    key[i - 1].put(j, fk.get(j));
    //                key[i - 1].put(len, key[i]);
    //            }
    //            forwardTree.deleteFromInsertCache(key, le, true);
    //        } else {
    //            forwardTree.deleteFromCache(data, false);
    //        }
    //    }

    //    public void deleteFromForwardDelete(Record data, int le) {
    //        if (le > 0) {
    //            Record[] key = new Record[le + 1];
    //            key[le] = data;
    //            Record fk = backTree[level - le - 1].getRecord(new CombKey(data, keyFields[le].length));
    //            key[le - 1] = new Record(nestKeySchemas[le - 1]);
    //            int len = keyFields[le - 1].length;
    //            for (int j = 0; j < len; j++)
    //                key[le - 1].put(j, fk.get(j));
    //            key[le - 1].put(len, key[le]);
    //            for (int i = (le - 1); i > 0; i--) {
    //                fk = getForeignKey(key[i], i, true);
    //                key[i - 1] = new Record(nestKeySchemas[i - 1]);
    //                len = keyFields[i - 1].length;
    //                for (int j = 0; j < len; j++)
    //                    key[i - 1].put(j, fk.get(j));
    //                key[i - 1].put(len, key[i]);
    //            }
    //            forwardTree.deleteFromDeleteCache(key, le, true);
    //        } else {
    //            forwardTree.deleteFromCache(data, false);
    //        }
    //    }

    //    public Record findForwardInsertCache(Record data, int le, boolean isKey) {
    //        if (le > 0) {
    //            Record[] key = new Record[le + 1];
    //            key[le] = data;
    //            for (int i = le; i > 0; i--) {
    //                key[i - 1] = getForeignKey(key[i], i, isKey);
    //            }
    //            Record former = forwardTree.getInsertCache(key[0], isKey);
    //            for (int i = 0; i < le; i++) {
    //                List<Record> rs = (List<Record>) former.get(keyFields[i].length);
    //                for (Record r : rs) {
    //                    if (new CombKey(r, keyFields[i + 1]).equals(new CombKey(key[i + 1], keyFields[i + 1]))) {
    //                        former = r;
    //                        break;
    //                    }
    //                }
    //            }
    //            return former;
    //        } else {
    //            return forwardTree.getInsertCache(data, true);
    //        }
    //    }

    //    private List<Record> addForward(List<Record> ff, Record[] key, int le, int recur) {
    //        MyComparator com = new MyComparator();
    //        if (recur == le) {
    //            if (ff == null) {
    //                ff = new ArrayList<Record>();
    //            }
    //            boolean find = false;
    //            int i = 0;
    //            for (Record rr : ff) {
    //                int cc = com.compare(key[recur], rr);
    //                if (cc <= 0) {
    //                    if (cc == 0)
    //                        find = true;
    //                    break;
    //                }
    //                i++;
    //            }
    //            if (!find) {
    //                ff.add(i, key[le]);
    //                Collections.sort(ff, com);
    //            }
    //            return ff;
    //        } else {
    //            for (int i = 0; i < ff.size(); i++) {
    //                Record rr = ff.get(i);
    //                if (com.compare(key[recur], rr) == 0) {
    //                    int size = rr.getSchema().getFields().size();
    //                    rr.put(size - 1, addForward((List<Record>) rr.get(size - 1), key, le, (recur + 1)));
    //                    ff.set(i, rr);
    //                    return ff;
    //                }
    //            }
    //            throw new ClassCastException(
    //                    "forward tree error: there is no match " + key[recur].getSchema().getName() + " key");
    //        }
    //    }

    //    private List<Record> deleteForward(List<Record> ff, Record[] key, int le, int recur) {
    //        MyComparator com = new MyComparator();
    //        if (recur == le) {
    //            for (int i = 0; i < ff.size(); i++) {
    //                Record rr = ff.get(i);
    //                if (com.compare(key[le], rr) == 0) {
    //                    ff.remove(i);
    //                    return (ff.size() == 0) ? null : ff;
    //                }
    //            }
    //        } else {
    //            for (int i = 0; i < ff.size(); i++) {
    //                Record rr = ff.get(i);
    //                if (com.compare(key[recur], rr) == 0) {
    //                    int size = rr.getSchema().getFields().size();
    //                    rr.put(size - 1, deleteForward((List<Record>) rr.get(size - 1), key, le, (recur + 1)));
    //                    ff.set(i, rr);
    //                    return ff;
    //                }
    //            }
    //        }
    //        throw new ClassCastException(
    //                "forward tree error: there is no match " + key[recur].getSchema().getName() + " key");
    //    }

    private void insertToCach(Record data, int le) {
        //        Record re = upperRecord(data, le);
        cachOperate(data, (byte) 1, le);
        //        backTree[level - le - 1].put(data);
        //        Record rr = new Record(nestKeySchemas[le]);
        //        setKey(rr, data, schemas[le].getKeyFields());
        if (le < (level - 1))
            forwardTree[le].insert(data, false);
        if (le > 0) {
            CombKey key = new CombKey(data, keyFields[le]);
            CombKey upper = new CombKey(data, schemas[le].getOutKeyFields());
            forwardTree[le - 1].insert(upper, key);
        }
        //        addToForward(data, le, false);
    }

    private void updateToCach(Record data, int le) throws IOException {
        if (le > 0 && backTree[level - le - 1].isbtree()) {
            CombKey upper = findForeignKey(new CombKey(data, keyFields[le]), le);
            int[] fields = schemas[le].getOutKeyFields();
            if (!isNullKey(data, fields)) {
                if (!upper.equals(new CombKey(data, fields))) {
                    Record dData = new Record(schemas[le].getSchema());
                    //                    int[] keyF = schemas[le].getKeyFields();
                    //                    for (int i = 0; i < keyFields[le].length; i++) {
                    //                        dData.put(i, data.get(keyFields[le][i]));
                    //                    }
                    for (int i = 0; i < fields.length; i++) {
                        dData.put(fields[i], upper.get(i));
                    }
                    //                    Record dd = new Record(nestKeySchemas[le]);
                    //                    setKey(dd, data, keyFields[le]);
                    //                    if (le < (level - 1)) {
                    //                        dd.put(nestKeySchemas[le].getFields().size() - 1, getAllNested(dData, le));
                    //                    }
                    //                    deleteFromForward(dData, le);
                    //                backTree[level - le - 1].put(data);
                    forwardTree[le - 1].delete(upper, new CombKey(data, keyFields[le]));
                    forwardTree[le - 1].insert(new CombKey(data, fields), new CombKey(data, keyFields[le]));
                    updateDelete(dData, le);
                    updateInsert(data, le);
                    //                    addToForward(dd, le);
                    return;
                }
            }
        }
        //        Record re = upperRecord(data, le);
        cachOperate(data, (byte) 3, le);
        //        backTree[level - le - 1].put(data, false);
    }

    private void updateDelete(Record data, int le) throws IOException {
        //Record re = upperRecord(data, le);
        //        Record rr = downRecord(data, le);
        cachOperate(data, (byte) 4, le);
    }

    private List<Record> getAllNested(Record data, int le) {
        assert (le < level);
        return forwardTree[le].find(new CombKey(data, keyFields[le]));
        //        if (le == 0) {
        //            return (List<Record>) forwardTree.find(data, false).get(nestKeySchemas[0].getFields().size() - 1);
        //        }
        //        assert (le < (level - 1));
        //        Record[] key = new Record[le + 1];
        //        key[le] = new Record(keySchemas[le]);
        //        int[] fields = schemas[le].getKeyFields();
        //        for (int i = 0; i < fields.length; i++) {
        //            key[le].put(i, data.get(fields[i]));
        //        }
        //        List<Field> fs = data.getSchema().getFields();
        //        key[le - 1] = backTree[level - le - 1].getRecord(key[le], true);
        //        for (int i = (le - 1); i > 0; i--) {
        //            key[i - 1] = getForeignKey(key[i], i, true);
        //        }
        //        Record former = forwardTree.find(key[0], true);
        //        List<Record> res = (List<Record>) former.get(nestKeySchemas[0].getFields().size() - 1);
        //        MyComparator com = new MyComparator();
        //        for (int i = 1; i < le; i++) {
        //            boolean find = false;
        //            for (Record r : res) {
        //                int cc = com.compare(key[i], r);
        //                if (cc <= 0) {
        //                    if (cc == 0) {
        //                        res = (List<Record>) r.get(nestKeySchemas[i].getFields().size() - 1);
        //                        find = true;
        //                    }
        //                    break;
        //                }
        //            }
        //            if (!find) {
        //                throw new ClassCastException(
        //                        "forward not match error: there is no " + nestKeySchemas[i].getName() + " key");
        //            }
        //        }
        //        boolean find = false;
        //        for (Record r : res) {
        //            if (com.compare(key[le], r) == 0) {
        //                Object v = r.get(nestKeySchemas[le].getFields().size() - 1);
        //                res = (v == null) ? null : (List<Record>) v;
        //            }
        //        }
        //        if (!find) {
        //            throw new ClassCastException(
        //                    "forward not match error: there is no " + nestKeySchemas[le].getName() + " key");
        //        }
        //        return res;
    }

    private void updateInsert(Record data, int le) throws IOException {
        //        if (le < (level - 1)) {
        //            List<Field> fs = new ArrayList<Field>();
        //            fs.add(new Schema.Field(schemas[le + 1].getSchema().getName() + "Arr",
        //                    Schema.createArray(schemas[le + 1].getSchema()), null, null));
        //            Schema vs = Schema.createRecord(schemas[le].getSchema().getName(), schemas[le].getSchema().getDoc(),
        //                    schemas[le].getSchema().getNamespace(), false, fs);
        //            Record rr = search(data, vs, false);
        //            Record res = new Record(schemas[le].getNestedSchema());
        //            int len = schemas[le].getSchema().getFields().size();
        //            for (int i = 0; i < len; i++) {
        //                res.put(i, data.get(i));
        //            }
        //            res.put(len, rr.get(0));
        //            extraCachOperate(upperRecord(res, le), (byte) 5, data, le);
        //        } else {
        //            extraCachOperate(upperRecord(data, le), (byte) 5, data, le);
        //        }
        extraCachOperate(data, (byte) 5, le);
    }

    private void deleteToCach(Record data, int le) throws IOException {
        //Record re = upperRecord(data, le);
        //use forwardTree to find the nested key
        //        Record re = downRecord(data, le);//not finished
        CombKey key = new CombKey(data, keyFields[le]);
        if (le < (level - 1)) {
            List<Record> nest = forwardTree[le].find(key);
            nestDelete(nest, le + 1, le);
            forwardTree[le].delete(key);
        }
        if (le > 0) {
            CombKey upper = findForeignKey(key, le);
            forwardTree[le - 1].delete(upper, key);
        }
        //        Record rr = new Record(nestKeySchemas[le]);
        //        setKey(rr, data, schemas[le].getKeyFields());
        //        deleteFromForward(data, le);
        cach.add(le, key, new FlagData((byte) 2, null, le));
        //        cachOperate(data, (byte) 2, le);
    }

    private void nestDelete(List<Record> nest, int le, int ne) {
        for (Record r : nest) {
            CombKey key = new CombKey(r);
            if (le < (level - 1)) {
                List<Record> nn = forwardTree[le].find(key);
                nestDelete(nn, le + 1, ne);
                forwardTree[le].delete(key);
            }
            //            Record rr = new Record(schemas[le].getSchema());
            //            setKey(rr, schemas[le].getKeyFields(), r);
            FlagData fd = cach.find(key, le);
            if (fd != null && fd.getFlag() == (byte) 4)
                cach.extraDelete(key, le);
            cach.add(le, key, new FlagData((byte) 2, null, ne));
            //            cach.add(r, (byte) 2, ne, le);
        }
    }

    private void cachOperate(Record data, byte flag, int le) {
        cach.add(data, flag, le);
    }

    private void extraCachOperate(Record data, byte flag, int le) {
        cach.extraAdd(data, flag, le);
    }

    //  private boolean exists(Record data, int le){
    //    byte b = cach.find(data, le);
    //    if(b == -1 || b == 2)
    //      return false;
    //    else
    //      return true;
    //  }

    public void load(NestSchema schema) throws IOException {
        File file = schema.getPrFile();
        int[] keyFields = schema.getKeyFields();
        Schema s = schema.getSchema();
        long start = System.currentTimeMillis();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        InsertAvroColumnWriter<ComparableKey, Record> writer = new InsertAvroColumnWriter<ComparableKey, Record>(s,
                resultPath, keyFields, free, mul);
        String line;
        while ((line = reader.readLine()) != null) {
            String[] tmp = line.split("\\|");
            Record record = arrToRecord(tmp, s);
            writer.append(new ComparableKey(record, keyFields), record);
        }
        reader.close();
        reader = null;
        int index = writer.flush();
        File[] files = new File[index];
        for (int i = 0; i < index; i++) {
            files[i] = new File(resultPath + "file" + String.valueOf(i) + ".trv");
        }
        if (index == 1) {
            merge(files);
            new File(resultPath + "file0.head").renameTo(new File(resultPath + "result.head"));
            new File(resultPath + "file0.trv").renameTo(new File(resultPath + "result.trv"));
        } else {
            merge(files);
            writer.mergeFiles(files, tmpPath);
        }

        deleteFile(schema.getPrFile().getPath());
        long end = System.currentTimeMillis();
        System.out.println(schema.getSchema().getName() + "\tsort trevni time: " + (end - start) + "ms");
    }

    public void createReader() throws IOException {
        reader = new ColumnReader<Record>(new File(resultPath + "result.trv"));
    }

    public void dLoad(NestSchema schema1, NestSchema schema2) throws IOException {
        int[] fields1 = keyJoin(schema1.getOutKeyFields(), schema1.getKeyFields());
        int numElements1 = toSortAvroFile(schema1, fields1);//write file1 according to (outkey+pkey) order
        int numElements2 = toSortAvroFile(schema2, schema2.getKeyFields());//write file2 according to pkey order

        BloomFilterBuilder builder1 = createBloom(numElements1, 1);
        BloomFilterBuilder builder2 = createBloom(numElements2, 0);
        //    backTree[0] = new BTreeRecord(schema1.getKeyFields(), schema1.getOutKeyFields(), schema1.getSchema(), schema1.getBTreeFile(),  "btree");

        //    BufferedReader reader1 = new BufferedReader(new FileReader(file1));
        //    BufferedReader reader2 = new BufferedReader(new FileReader(file2));
        long start = System.currentTimeMillis();
        SortedAvroReader reader1 = new SortedAvroReader(schema1.getPath(), schema1.getEncodeSchema(), fields1);
        SortedAvroReader reader2 = new SortedAvroReader(schema2.getPath(), schema2.getEncodeSchema(),
                schema2.getKeyFields());
        InsertAvroColumnWriter<ComparableKey, Record> writer = new InsertAvroColumnWriter<ComparableKey, Record>(
                schema2.getNestedSchema(), resultPath, schema2.getKeyFields(), free, mul);

        Record record1 = reader1.next();
        builder1.add(record1);
        backTree[0].put(record1);
        while (reader2.hasNext()) {
            Record record2 = reader2.next();
            builder2.add(record2);
            ComparableKey k2 = new ComparableKey(record2, schema2.getKeyFields());
            List<Record> arr = new ArrayList<Record>();
            while (true) {
                ComparableKey k1 = new ComparableKey(record1, schema1.getOutKeyFields());
                if (k2.compareTo(k1) == 0) {
                    arr.add(record1);
                    if (reader1.hasNext()) {
                        record1 = reader1.next();
                        builder1.add(record1);
                        backTree[0].put(record1);
                        continue;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            Record record = join(schema2.getEncodeNestedSchema(), record2, arr);
            writer.append(k2, record);
        }
        builder1.write();
        builder2.write();
        backTree[0].write();
        reader1.close();
        reader2.close();
        reader1 = null;
        reader2 = null;
        int index = writer.flush();
        File[] files = new File[index];
        for (int i = 0; i < index; i++) {
            files[i] = new File(resultPath + "file" + String.valueOf(i) + ".trv");
        }
        if (index == 1) {
            merge(files);
            new File(resultPath + "file0.head").renameTo(new File(resultPath + "result.head"));
            new File(resultPath + "file0.trv").renameTo(new File(resultPath + "result.trv"));
        } else {
            merge(files);
            writer.mergeFiles(files, tmpPath);
        }

        deleteFile(schema1.getPath());
        deleteFile(schema2.getPath());
        long end = System.currentTimeMillis();
        System.out.println(schema2.getSchema().getName() + "+" + schema1.getSchema().getName() + "\tsort trevni time: "
                + (end - start) + "ms");
    }

    public Record join(Schema schema, Record record, List<Record> arr) {
        Record result = new Record(schema);
        List<Field> fs = schema.getFields();
        for (int i = 0; i < fs.size() - 1; i++) {
            result.put(i, record.get(i));
            //      switch(fs.get(i).schema().getType()){
            //      case STRING:  {result.put(i, record.get(i));  break;}
            //      case BYTES:  {result.put(i, ByteBuffer.wrap(record.get(i).toString().getBytes()));  break;  }
            //      case INT:  {result.put(i, Integer.parseInt(record.get(i).toString()));  break;  }
            //      case LONG:  {result.put(i, Long.parseLong(record.get(i).toString()));  break;  }
            //      case FLOAT:  {result.put(i, Float.parseFloat(record.get(i).toString()));  break;  }
            //      case DOUBLE:  {result.put(i, Double.parseDouble(record.get(i).toString()));  break;  }
            //      case BOOLEAN:  {result.put(i, Boolean.getBoolean(record.get(i).toString()));  break;  }
            //      default:  {throw new ClassCastException("This type "+fs.get(i).schema().getType()+" is not supported!");  }
            //      }
        }
        result.put(fs.size() - 1, arr);
        return result;
    }

    public void prLoad(NestSchema schema1, NestSchema schema2) throws IOException {
        int[] fields1 = keyJoin(schema1.getOutKeyFields(), schema1.getKeyFields());
        int numElements1 = toSortAvroFile(schema1, fields1);//write file1 according to (outkey+pkey) order
        int numElements2 = toSortAvroFile(schema2, schema2.getKeyFields());//write file2 according to pkey order

        BloomFilterBuilder builder1 = createBloom(numElements1, (level - 1));
        BloomFilterBuilder builder2 = createBloom(numElements2, (level - 2));
        //    backTree[0] = new BTreeRecord(schema1.getKeyFields(), schema1.getOutKeyFields(), schema1.getSchema(), schema1.getBTreeFile(),  "btree");
        //    backTree[1] = new BTreeRecord(schema2.getKeyFields(), schema2.getOutKeyFields(), schema2.getSchema(), schema2.getBTreeFile(),  "btree");

        long start = System.currentTimeMillis();
        SortedAvroReader reader1 = new SortedAvroReader(schema1.getPath(), schema1.getEncodeSchema(), fields1);
        SortedAvroReader reader2 = new SortedAvroReader(schema2.getPath(), schema2.getEncodeSchema(),
                schema2.getKeyFields());
        SortedAvroWriter<ComparableKey, Record> writer = new SortedAvroWriter<ComparableKey, Record>(tmpPath,
                schema2.getEncodeNestedSchema(), free, mul);
        int[] sortFields = keyJoin(schema2.getOutKeyFields(), schema2.getKeyFields());

        offsetTree[level - 1].create((int) (numElements1 / 500));
        offsetTree[level - 2].create((int) (numElements2 / 500));
        backTree[0].create((int) (numElements1 / 500));
        backTree[1].create((int) (numElements2 / 500));
        forwardTree[level - 2].create((int) (numElements2 / 500));
        Record record1 = reader1.next();
        builder1.add(record1);
        backTree[0].put(record1);
        while (reader2.hasNext()) {
            Record record2 = reader2.next();
            builder2.add(record2);
            backTree[1].put(record2);
            ComparableKey k2 = new ComparableKey(record2, schema2.getKeyFields());
            List<Record> arr = new ArrayList<Record>();
            List<Record> v = new ArrayList<Record>();
            while (true) {
                ComparableKey k1 = new ComparableKey(record1, schema1.getOutKeyFields());
                if (k2.compareTo(k1) == 0) {
                    arr.add(record1);
                    Record aa = new Record(keySchemas[level - 1]);
                    setKey(aa, record1, keyFields[level - 1]);
                    v.add(aa);
                    if (reader1.hasNext()) {
                        record1 = reader1.next();
                        builder1.add(record1);
                        backTree[0].put(record1);
                        continue;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            Record record = join(schema2.getEncodeNestedSchema(), record2, arr);
            Record k = new Record(keySchemas[level - 2]);
            setKey(k, record2, keyFields[level - 2]);
            forwardTree[level - 2].put(k, v);
            writer.append(new ComparableKey(record, sortFields), record);
        }
        builder1.write();
        builder2.write();
        backTree[0].write();
        backTree[1].write();
        forwardTree[level - 2].close();
        reader1.close();
        reader2.close();
        reader1 = null;
        reader2 = null;
        writer.flush();
        deleteFile(schema1.getPath());
        deleteFile(schema2.getPath());
        moveTo(tmpPath, schema2.getPath());
        long end = System.currentTimeMillis();
        System.out.println(schema2.getSchema().getName() + "+" + schema1.getSchema().getName() + "\tsort avro time: "
                + (end - start) + "ms");
    }

    public void orLoad(NestSchema schema1, NestSchema schema2, int index) throws IOException {
        int numElements2 = toSortAvroFile(schema2, schema2.getKeyFields());//write file2 according to pkey order
        BloomFilterBuilder builder2 = createBloom(numElements2, (index - 1));
        int bin = level - index - 1;
        //    backTree[index] = new BTreeRecord(schema2.getKeyFields(), schema2.getOutKeyFields(), schema2.getSchema(), schema2.getBTreeFile(),  "btree");
        //将file1,file2按照file1的外键排序
        long start = System.currentTimeMillis();
        SortedAvroReader reader1 = new SortedAvroReader(schema1.getPath(), schema1.getEncodeNestedSchema(),
                keyJoin(schema1.getOutKeyFields(), schema1.getKeyFields()));
        SortedAvroReader reader2 = new SortedAvroReader(schema2.getPath(), schema2.getEncodeSchema(),
                schema2.getKeyFields());
        SortedAvroWriter<ComparableKey, Record> writer = new SortedAvroWriter<ComparableKey, Record>(tmpPath,
                schema2.getEncodeNestedSchema(), free, mul);
        int[] sortFields = keyJoin(schema2.getOutKeyFields(), schema2.getKeyFields());
        //BufferedWriter out = new BufferedWriter(new FileWriter(new File("/home/ly/tmp.avro")));

        offsetTree[index - 1].create((int) (numElements2 / 500));
        backTree[bin].create((int) (numElements2 / 500));
        forwardTree[index - 1].create((int) (numElements2 / 500));
        Record record1 = reader1.next();
        while (reader2.hasNext()) {
            Record record2 = reader2.next();
            builder2.add(record2);
            backTree[bin].put(record2);
            ComparableKey k2 = new ComparableKey(record2, schema2.getKeyFields());
            List<Record> arr = new ArrayList<Record>();
            List<Record> v = new ArrayList<Record>();
            while (true) {
                ComparableKey k1 = new ComparableKey(record1, schema1.getOutKeyFields());
                if (k2.compareTo(k1) == 0) {
                    arr.add(record1);
                    Record aa = new Record(keySchemas[index]);
                    setKey(aa, record1, keyFields[index]);
                    v.add(aa);
                    if (reader1.hasNext()) {
                        record1 = reader1.next();
                        continue;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            Record record = join(schema2.getEncodeNestedSchema(), record2, arr);
            Record k = new Record(keySchemas[index - 1]);
            setKey(k, record2, keyFields[index - 1]);
            forwardTree[index - 1].put(k, v);
            writer.append(new ComparableKey(record, sortFields), record);
        }
        backTree[bin].write();
        forwardTree[index - 1].close();
        builder2.write();
        reader1.close();
        reader2.close();
        reader1 = null;
        reader2 = null;
        writer.flush();
        deleteFile(schema1.getPath());
        deleteFile(schema2.getPath());
        moveTo(tmpPath, schema2.getPath());
        long end = System.currentTimeMillis();
        System.out.println(schema2.getSchema().getName() + "+" + schema1.getSchema().getName() + "\tsort avro time: "
                + (end - start) + "ms");
    }

    public void laLoad(NestSchema schema1, NestSchema schema2) throws IOException {
        int numElements2 = toSortAvroFile(schema2, schema2.getKeyFields());//write file2 according to pkey order
        BloomFilterBuilder builder2 = createBloom(numElements2, 0);

        long start = System.currentTimeMillis();
        SortedAvroReader reader1 = new SortedAvroReader(schema1.getPath(), schema1.getEncodeNestedSchema(),
                keyJoin(schema1.getOutKeyFields(), schema1.getKeyFields()));
        SortedAvroReader reader2 = new SortedAvroReader(schema2.getPath(), schema2.getEncodeSchema(),
                schema2.getKeyFields());
        InsertAvroColumnWriter<ComparableKey, Record> writer = new InsertAvroColumnWriter<ComparableKey, Record>(
                schema2.getNestedSchema(), resultPath, schema2.getKeyFields(), free, mul);

        offsetTree[0].create((int) (numElements2 / 500));
        forwardTree[0].create((int) (numElements2 / 500));
        Record record1 = reader1.next();
        while (reader2.hasNext()) {
            Record record2 = reader2.next();
            builder2.add(record2);
            ComparableKey k2 = new ComparableKey(record2, schema2.getKeyFields());
            List<Record> arr = new ArrayList<Record>();
            List<Record> v = new ArrayList<Record>();
            while (true) {
                ComparableKey k1 = new ComparableKey(record1, schema1.getOutKeyFields());
                if (k2.compareTo(k1) == 0) {
                    arr.add(record1);
                    Record aa = new Record(keySchemas[1]);
                    setKey(aa, record1, keyFields[1]);
                    v.add(aa);
                    if (reader1.hasNext()) {
                        record1 = reader1.next();
                        continue;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            Record record = join(schema2.getEncodeNestedSchema(), record2, arr);
            Record k = new Record(keySchemas[0]);
            setKey(k, record2, keyFields[0]);
            forwardTree[0].put(k, v);
            writer.append(k2, record);
        }
        builder2.write();
        reader1.close();
        reader2.close();
        reader1 = null;
        reader2 = null;
        forwardTree[0].close();
        int index = writer.flush();
        File[] files = new File[index];
        for (int i = 0; i < index; i++) {
            files[i] = new File(resultPath + "file" + String.valueOf(i) + ".trv");
        }
        if (index == 1) {
            merge(files);
            new File(resultPath + "file0.head").renameTo(new File(resultPath + "result.head"));
            new File(resultPath + "file0.trv").renameTo(new File(resultPath + "result.trv"));
        } else {
            merge(files);
            writer.mergeFiles(files, tmpPath);
        }

        deleteFile(schema1.getPath());
        deleteFile(schema2.getPath());
        long end = System.currentTimeMillis();
        System.out.println(schema2.getSchema().getName() + "+" + schema1.getSchema().getName() + "\tsort trevni time: "
                + (end - start) + "ms");
    }

    public void merge(File[] files) throws IOException {
        long t1 = System.currentTimeMillis();
        int level = schemas.length;
        int[] index = new int[schemas.length];
        //    Schema s = nestKeySchema;
        int[] la = new int[level - 1];
        for (int i = 0; i < la.length; i++) {
            la[i] = schemas[i].getKeyFields().length;
        }
        SortTrevniReader re = new SortTrevniReader(files, nestKeySchemas[0], tmpPath);
        while (re.hasNext()) {
            Record record = re.next().getRecord();
            if (level > 1)
                //                forwardTree.put(record);
                offsetTree[0].put(record, index[0]++, true);
            List<Record> rs = (List<Record>) record.get(la[0]);
            for (int i = 1; i < level - 1; i++) {
                List<Record> tmp = new ArrayList<Record>();
                tmp.addAll(rs);
                rs.clear();
                for (int k = 0; k < tmp.size(); k++) {
                    Record r = tmp.get(k);
                    rs.addAll((List<Record>) r.get(la[i]));
                    offsetTree[i].put(r, index[i], true);
                    index[i]++;
                }
            }
            for (int k = 0; k < rs.size(); k++) {
                offsetTree[level - 1].put(rs.get(k), index[level - 1], true);
                index[level - 1]++;
            }
        }
        //        if (level > 1)
        //            forwardTree.close();
        for (int i = 0; i < offsetTree.length; i++) {
            offsetTree[i].write();
        }
        re.close();
        re = null;
        long t2 = System.currentTimeMillis();
        System.out.println("$$$merge read + btree time" + (t2 - t1));
        //        return re.getGap();
    }

    public void deleteFile(String path) throws IOException {
        File file = new File(path);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (int i = 0; i < files.length; i++) {
                deleteFile(files[i].getPath());
            }
        } else {
            //            file.delete();
            shDelete(path);
        }
    }

    public static String writeKeyRecord(Record record) {
        StringBuilder str = new StringBuilder();
        str.append("{");
        Schema s = record.getSchema();
        List<Field> fs = s.getFields();
        int len = fs.size();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                if (isSimple(fs.get(i))) {
                    str.append(record.get(i));
                } else {
                    if (fs.get(i).schema().getType() == Type.ARRAY) {
                        List<Record> rs = (List<Record>) record.get(i);
                        if (rs != null) {
                            int l = rs.size();
                            if (l > 0) {
                                str.append(writeKeyRecord(rs.get(0)));
                                for (int j = 1; j < l; j++) {
                                    str.append(",");
                                    str.append(writeKeyRecord(rs.get(j)));
                                }
                            }
                        }
                    }
                }
                if (i < len - 1) {
                    str.append("|");
                }
            }
        }
        str.append("}");
        return str.toString();
    }

    public static Record readKeyRecord(Schema s, String str) {
        char[] ss = str.toCharArray();
        assert (ss[0] == '{' && ss[ss.length - 1] == '}');
        int index = 1;
        Record r = new Record(s);
        int i = 0;
        for (Field f : s.getFields()) {
            if (isSimple(f)) {
                StringBuilder v = new StringBuilder();
                while (index < (ss.length - 1) && ss[index] != '|') {
                    v.append(ss[index]);
                    index++;
                }
                r.put(i++, getValue(f, v.toString()));
            } else {
                List<Record> record = new ArrayList<Record>();
                while (index < ss.length - 1) {
                    assert (ss[index] == '{');
                    index++;
                    int tt = 1;
                    StringBuilder xx = new StringBuilder();
                    xx.append('{');
                    while (tt > 0) {
                        if (ss[index] == '{') {
                            tt++;
                        }
                        if (ss[index] == '}') {
                            tt--;
                        }
                        xx.append(ss[index]);
                        index++;
                    }
                    record.add(readKeyRecord(f.schema().getElementType(), xx.toString()));
                    if (ss[index] == ',') {
                        index++;
                    } else {
                        break;
                    }
                }
                r.put(i++, record);
            }
            if (i < s.getFields().size()) {
                assert (ss[index] == '|');
                index++;
            }
        }
        assert (index == ss.length - 1);
        return r;
    }

    public static boolean isSimple(Field f) {
        switch (f.schema().getType()) {
            case INT:
            case LONG:
            case STRING:
            case BYTES:
                return true;
            case ARRAY:
                return false;
        }
        throw new ClassCastException("cannot support the key type:" + f.schema().getType());
    }

    public static Object getValue(Field f, String s) {
        if (s.equals(""))
            return null;
        switch (f.schema().getType()) {
            case INT:
                return Integer.parseInt(s);
            case LONG:
                return Long.parseLong(s);
            case STRING:
            case BYTES:
                return s;
        }
        throw new ClassCastException("cannot support the key type:" + f.schema().getType());
    }

    public void moveTo(String path, String toPath) {
        File file = new File(path);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (int i = 0; i < files.length; i++) {
                files[i].renameTo(new File(toPath + files[i].getName()));
            }
        }
    }

    public int toSortAvroFile(NestSchema schema, int[] keyFields) throws IOException {
        int numElements = 0;
        long start = System.currentTimeMillis();
        File file = schema.getPrFile();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        SortedAvroWriter<ComparableKey, Record> writer = new SortedAvroWriter<ComparableKey, Record>(schema.getPath(),
                schema.getEncodeSchema(), free, mul);
        String line;
        while ((line = reader.readLine()) != null) {
            String[] tmp = line.split("\\|");
            numElements++;
            Record record = arrToRecord(tmp, schema.getEncodeSchema());
            writer.append(new ComparableKey(record, keyFields), record);
        }
        reader.close();
        writer.flush();
        //        deleteFile(schema.getPrFile().getPath());
        long end = System.currentTimeMillis();
        System.out.println(schema.getSchema().getName() + "\tsort avro time: " + (end - start) + "ms");
        return numElements;
    }

    public int[] keyJoin(int[] key1, int[] key2) {
        int len1 = key1.length;
        int len2 = key2.length;
        int[] result = new int[(len1 + len2)];
        for (int i = 0; i < len1; i++) {
            result[i] = key1[i];
        }
        for (int i = 0; i < len2; i++) {
            result[(i + len1)] = key2[i];
        }
        return result;
    }

    public Record arrToRecord(String[] arr, Schema s) {
        Record record = new Record(s);
        List<Field> fs = s.getFields();
        for (int i = 0; i < arr.length; i++) {
            switch (fs.get(i).schema().getType()) {
                case STRING: {
                    record.put(i, arr[i]);
                    break;
                }
                case BYTES: {
                    record.put(i, ByteBuffer.wrap(arr[i].getBytes()));
                    break;
                }
                case INT: {
                    record.put(i, Integer.parseInt(arr[i]));
                    break;
                }
                case LONG: {
                    record.put(i, Long.parseLong(arr[i]));
                    break;
                }
                case FLOAT: {
                    record.put(i, Float.parseFloat(arr[i]));
                    break;
                }
                case DOUBLE: {
                    record.put(i, Double.parseDouble(arr[i]));
                    break;
                }
                case BOOLEAN: {
                    record.put(i, Boolean.getBoolean(arr[i]));
                    break;
                }
                default: {
                    throw new ClassCastException("This type " + fs.get(i).schema().getType() + " is not supported!");
                }
            }
        }
        return record;
    }

    public long getNumLines(File file) throws IOException {
        long len = 0;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        while (reader.readLine() != null) {
            len++;
        }
        reader.close();
        return len;
    }
}