/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.trevni.update;

import java.io.IOException;

public class BlockDescriptor {
    int rowCount;
    int uncompressedSize;
    int compressedSize;

    BlockDescriptor() {
    }

    public BlockDescriptor(int rowCount, int uncompressedSize, int compressedSize) {
        this.rowCount = rowCount;
        this.uncompressedSize = uncompressedSize;
        this.compressedSize = compressedSize;
    }

    public int getSize() {
        return compressedSize;
    }

    public void writeTo(OutputBuffer out) throws IOException {
        out.writeFixed32(rowCount);
        out.writeFixed32(uncompressedSize);
        out.writeFixed32(compressedSize);
    }

    public static BlockDescriptor read(InputBuffer in) throws IOException {
        BlockDescriptor result = new BlockDescriptor();
        result.rowCount = in.readFixed32();
        result.uncompressedSize = in.readFixed32();
        result.compressedSize = in.readFixed32();
        return result;
    }

}
