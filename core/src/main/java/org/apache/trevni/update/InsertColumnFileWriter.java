package org.apache.trevni.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import org.apache.trevni.ValueType;

public class InsertColumnFileWriter {
    private FileColumnMetaData[] meta;
    private FileMetaData filemeta;
    private File[] files;
    private InsertColumnFileReader[] readers;
    private ListArr[] insert;
    private int rowcount;
    private int columncount;
    private String path;
    //    private int[] gap;
    private RandomAccessFile gapFile;
    //    private int[] nest;
    private RandomAccessFile nestFile;
    private long[] columnStart;
    private Blocks[] blocks;

    private int addRow;
    static final byte[] MAGIC = new byte[] { 'T', 'r', 'v', 2 };

    class Blocks {
        private List<BlockDescriptor> blocks;

        Blocks() {
            blocks = new ArrayList<BlockDescriptor>();
        }

        void add(BlockDescriptor b) {
            blocks.add(b);
        }

        void clear() {
            blocks.clear();
        }

        BlockDescriptor get(int i) {
            return blocks.get(i);
        }
    }

    public static class ListArr {
        private List<Object> x;

        public ListArr() {
            this.x = new ArrayList<Object>();
        }

        public void add(Object o) {
            this.x.add(o);
        }

        public void clear() {
            x.clear();
        }

        public Object get(int i) {
            return x.get(i);
        }

        public Object[] toArray() {
            return x.toArray();
        }

        public int size() {
            return x.size();
        }
    }

    // public static void MemPrint(){
    //     System.out.println("$$$$$$$$$\t"+(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
    // }

    //  public InsertColumnFileWriter(File fromfile, ListArr[] sort) throws IOException {
    //    this.reader = new InsertColumnFileReader(fromfile);
    //    this.insert = sort;
    //    this.filemeta = reader.getMetaData();
    //    this.meta = reader.getFileColumnMetaData();
    //    this.addRow = sort[0].size();
    //  }

    public InsertColumnFileWriter(FileMetaData filemeta, FileColumnMetaData[] meta) throws IOException {
        this.filemeta = filemeta;
        this.meta = meta;
        this.columncount = meta.length;
        this.columnStart = new long[columncount];
        this.blocks = new Blocks[columncount];
        for (int i = 0; i < columncount; i++) {
            blocks[i] = new Blocks();
        }
    }

    public void setMergeFiles(File[] files) throws IOException {
        this.files = files;
        readers = new InsertColumnFileReader[files.length];
        for (int i = 0; i < files.length; i++) {
            readers[i] = new InsertColumnFileReader(files[i]);
        }
    }

    public void setInsert(ListArr[] sort) {
        this.insert = sort;
        this.addRow = sort[0].size();
    }

    public void setGap(String path) throws IOException {
        this.path = path;
        this.gapFile = new RandomAccessFile(path + "gap", "rw");
        this.nestFile = new RandomAccessFile(path + "nest", "rw");
    }
    //    public void setGap(int[] gap) {
    //        this.gap = gap;
    //    }

    public void appendTo(File file) throws IOException {
        OutputStream data = new FileOutputStream(file);
        OutputStream head = new FileOutputStream(
                new File(file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(".")) + ".head"));
        appendTo(head, data);
    }

    //  public void insertTo(File file) throws IOException {
    //    OutputStream data = new FileOutputStream(file);
    //    OutputStream head = new FileOutputStream(new File(file.getPath().substring(0, file.getPath().lastIndexOf(".")) + ".head"));
    //    insertTo(head, data);
    //  }

    public void appendTo(OutputStream head, OutputStream data) throws IOException {
        rowcount = addRow;

        writeSourceColumns(data);
        writeHeader(head);
    }

    public void mergeFiles(File file) throws IOException {
        mergeFiles(
                new FileOutputStream(new File(
                        file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(".")) + ".head")),
                new FileOutputStream(file));
    }

    public void mergeFiles(OutputStream head, OutputStream data) throws IOException {
        rowcount = gapFile.readInt();
        //        nest = new int[rowcount];
        //        for (int i = 0; i < rowcount; i++) {
        //            nest[i] = 1;
        //        }
        for (int i = 0; i < columncount; i++) {
            if (meta[i].getType() == ValueType.NULL) {
                mergeArrayColumn(data, i);
            } else {
                mergeColumn(data, i);
            }
        }
        writeHeader(head);
        for (int i = 0; i < readers.length; i++) {
            readers[i].close();
            readers[i] = null;
            //            files[i].delete();
            //            new File(files[i].getAbsolutePath().substring(0, files[i].getAbsolutePath().lastIndexOf(".")) + ".head")
            //                    .delete();
        }
        clearGap();
    }

    private void mergeColumn(OutputStream out, int column) throws IOException {
        OutputBuffer buf = new OutputBuffer();
        gapFile.seek(4);
        nestFile.seek(0);
        int row = 0;
        ColumnValues[] values = new ColumnValues[readers.length];
        for (int i = 0; i < readers.length; i++) {
            values[i] = readers[i].getValues(column);
        }
        ValueType type = meta[column].getType();

        for (int i = 0; i < rowcount; i++) {
            int index = gapFile.readInt();
            int nest = nestFile.readInt();
            //            int index = gap[i];
            for (int j = 0; j < nest; j++) {
                if (buf.isFull()) {
                    BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
                    blocks[column].add(b);
                    row = 0;
                    buf.writeTo(out);
                    buf.reset();
                }
                values[index].startRow();
                buf.writeValue(values[index].nextValue(), type);
                row++;
            }
        }

        if (buf.size() != 0) {
            BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
            blocks[column].add(b);
            buf.writeTo(out);
        }

        buf.close();
    }

    private void mergeArrayColumn(OutputStream out, int column) throws IOException {
        OutputBuffer buf = new OutputBuffer();
        gapFile.seek(4);
        nestFile.seek(0);
        int row = 0;
        ColumnValues[] values = new ColumnValues[readers.length];
        for (int i = 0; i < readers.length; i++) {
            values[i] = readers[i].getValues(column);
        }
        RandomAccessFile tmpNestFile = new RandomAccessFile(path + "tmpnest", "rw");

        for (int i = 0; i < rowcount; i++) {
            int index = gapFile.readInt();
            int nest = nestFile.readInt();
            //            int index = gap[i];
            //            nest[i] = 0;
            int tmpnest = 0;
            for (int j = 0; j < nest; j++) {
                if (buf.isFull()) {
                    BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
                    blocks[column].add(b);
                    row = 0;
                    buf.writeTo(out);
                    buf.reset();
                }
                values[index].startRow();
                int length = values[index].nextLength();
                //                nest[i] += length;
                tmpnest += length;
                buf.writeLength(length);
                row++;
            }
            tmpNestFile.writeInt(tmpnest);
        }

        nestFile.close();
        nestFile = null;
        tmpNestFile.close();
        tmpNestFile = null;
        new File(path + "nest").delete();
        new File(path + "tmpnest").renameTo(new File(path + "nest"));
        nestFile = new RandomAccessFile(path + "nest", "rw");

        if (buf.size() != 0) {
            BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
            blocks[column].add(b);
            buf.writeTo(out);
        }

        buf.close();
    }

    //  public void insertTo(OutputStream head, OutputStream data) throws IOException {
    //    rowcount = addRow + reader.getRowCount();
    //    values = new ColumnValues[meta.length];
    //    for(int i = 0; i < meta.length; i++){
    //      values[i] = reader.getValues(i);
    //    }
    //
    //    writeColumns(data);
    //    writeHeader(head);
    //  }

    private void writeSourceColumns(OutputStream out) throws IOException {
        OutputBuffer buf = new OutputBuffer();
        for (int i = 0; i < columncount; i++) {
            ValueType type = meta[i].getType();
            int row = 0;
            if (type == ValueType.NULL) {
                for (Object x : insert[i].toArray()) {
                    if (buf.isFull()) {
                        BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
                        blocks[i].add(b);
                        row = 0;
                        buf.writeTo(out);
                        buf.reset();
                    }
                    buf.writeLength((Integer) x);
                    row++;
                }
            } else {
                for (Object x : insert[i].toArray()) {
                    if (buf.isFull()) {
                        BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
                        blocks[i].add(b);
                        row = 0;
                        buf.writeTo(out);
                        buf.reset();
                    }
                    buf.writeValue(x, type);
                    row++;
                }
            }

            insert[i].clear();

            if (buf.size() != 0) {
                BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
                blocks[i].add(b);
                buf.writeTo(out);
                buf.reset();
            }
        }
        insert = null;
        buf.close();
    }

    //  private void writeColumns(OutputStream out) throws IOException {
    //    assert(gap.length == (addRow + 1));
    //    nest = new int[addRow];
    //    for (int k = 0; k < addRow; k++) {
    //      nest[k] = 1;
    //    }
    //
    //    for (int j = 0; j < columncount; j++) {
    //      if (meta[j].getType() == ValueType.NULL) {
    //        writeArrayColumn(out, j);
    //      } else {
    //        writeColumn(out, j);
    //      }
    //    }
    ////        MemPrint();
    ////    reader.close();
    ////    reader = null;
    ////    values = null;
    //    insert = null;
    //    gap = null;
    //    nest = null;
    //  }

    //  private void writeColumn(OutputStream out, int column) throws IOException {
    //    OutputBuffer buf = new OutputBuffer();
    //    int row = 0;
    //    int realrow = 0;
    //    ValueType type = meta[column].getType();
    //
    //    for (int i = 0; i < addRow; i++) {
    //      for (int k = 0; k < gap[i]; k++) {
    //        if(buf.isFull()){
    //          BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //          blocks[column].add(b);
    //          row = 0;
    //          buf.writeTo(out);
    //          buf.reset();
    //        }
    //        values[column].startRow();
    //        buf.writeValue(values[column].nextValue(), type);
    //        row++;
    //      }
    //      for (int r = 0; r < nest[i]; r++) {
    //        if(buf.isFull()){
    //          BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //          blocks[column].add(b);
    //          row = 0;
    //          buf.writeTo(out);
    //          buf.reset();
    //        }
    //        buf.writeValue(insert[column].get(realrow), type);
    //        row++;
    //        realrow++;
    //      }
    //    }
    //    assert (realrow == insert[column].size());
    //    insert[column].clear();
    //
    //    for (int k = 0; k < gap[addRow]; k++) {
    //      if(buf.isFull()){
    //        BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //        blocks[column].add(b);
    //        row = 0;
    //        buf.writeTo(out);
    //        buf.reset();
    //      }
    //      values[column].startRow();
    //      buf.writeValue(values[column].nextValue(), type);
    //      row++;
    //    }
    //
    //    if(buf.size() != 0){
    //      BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //      blocks[column].add(b);
    //      buf.writeTo(out);
    //    }
    //
    //    buf.close();
    //  }

    //  private void writeArrayColumn(OutputStream out, int column) throws IOException {
    //    OutputBuffer buf = new OutputBuffer();
    //    int row = 0;
    //    int realrow = 0;
    //    long[] tmgap = new long[addRow + 1];
    //    int[] tmnest = new int[addRow];
    //    ValueType type = meta[column].getType();
    //    if(type == ValueType.NULL){
    //      int y = 0;
    //      for(int x = 0; x <addRow; x++){
    //        for(int no = 0; no < nest[x]; no++){
    //          tmnest[x] += (Integer) insert[column].get(y + no);
    //        }
    //        y += nest[x];
    //      }
    //    }
    //
    //    for (int i = 0; i < addRow; i++) {
    //      for (long k = 0; k < gap[i]; k++) {
    //        if(buf.isFull()){
    //          BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //          blocks[column].add(b);
    //          row = 0;
    //          buf.writeTo(out);
    //          buf.reset();
    //        }
    //        values[column].startRow();
    //        int length = values[column].nextLength();
    //        buf.writeLength(length);
    //        tmgap[i] += length;
    //        row++;
    //      }
    //      for (int r = 0; r < nest[i]; r++) {
    //        if(buf.isFull()){
    //          BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //          blocks[column].add(b);
    //          row = 0;
    //          buf.writeTo(out);
    //          buf.reset();
    //        }
    //        buf.writeLength((Integer) insert[column].get(realrow));
    //        realrow++;
    //        row++;
    //      }
    //    }
    //    assert (realrow == insert[column].size());
    //    insert[column].clear();
    //
    //    for (long k = 0; k < gap[addRow]; k++) {
    //      if(buf.isFull()){
    //        BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //        blocks[column].add(b);
    //        row = 0;
    //        buf.writeTo(out);
    //        buf.reset();
    //      }
    //      values[column].startRow();
    //      int len = values[column].nextLength();
    //      buf.writeLength(len);
    //      tmgap[addRow] += len;
    //      row++;
    //    }
    //    nest = tmnest;
    //    gap = tmgap;
    //
    //    if(buf.size() != 0){
    //      BlockDescriptor b = new BlockDescriptor(row, buf.size(), buf.size());
    //      blocks[column].add(b);
    //      buf.writeTo(out);
    //    }
    //
    //    buf.close();
    //  }

    public void writeHeader(OutputStream out) throws IOException {
        OutputBuffer header = new OutputBuffer();
        header.write(MAGIC);
        header.writeFixed32(rowcount);
        header.writeFixed32(columncount);
        filemeta.write(header);
        int i = 0;
        long delay = 0;
        for (FileColumnMetaData c : meta) {
            columnStart[i] = delay;
            c.write(header);
            int size = blocks[i].blocks.size();
            header.writeFixed32(size);
            for (int k = 0; k < size; k++) {
                blocks[i].get(k).writeTo(header);
                delay += blocks[i].get(k).compressedSize;
            }
            blocks[i].clear();
            i++;
        }

        for (i = 0; i < columncount; i++) {
            header.writeFixed64(columnStart[i]);
        }
        header.writeTo(out);
        header.close();
    }

    public void clearGap() throws IOException {
        gapFile.close();
        nestFile.close();
        gapFile = null;
        nestFile = null;
        new File(path + "gap").delete();
        new File(path + "nest").delete();
    }
}