/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.nodes.input;

import static com.oracle.truffle.regex.tregex.string.Encodings.Encoding;

import com.oracle.truffle.api.ArrayUtils;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;

public abstract class InputRegionMatchesNode extends Node {

    public static InputRegionMatchesNode create() {
        return InputRegionMatchesNodeGen.create();
    }

    public abstract boolean execute(Object input, int fromIndex1, Object match, int fromIndex2, int length, Object mask, Encoding encoding);

    @Specialization(guards = "mask == null")
    public boolean doString(String input, int fromIndex1, String match, int fromIndex2, int length, @SuppressWarnings("unused") Object mask, @SuppressWarnings("unused") Encoding encoding) {
        return input.regionMatches(fromIndex1, match, fromIndex2, length);
    }

    @Specialization(guards = "mask != null")
    public boolean doJavaStringMask(String input, int fromIndex1, String match, int fromIndex2, int length, String mask, @SuppressWarnings("unused") Encoding encoding) {
        return ArrayUtils.regionEqualsWithOrMask(input, fromIndex1, match, fromIndex2, length, mask);
    }

    @Specialization(guards = "mask == null")
    public boolean doTString(TruffleString input, int fromIndex1, TruffleString match, int fromIndex2, int length, @SuppressWarnings("unused") Object mask, Encoding encoding,
                    @Cached TruffleString.RegionEqualByteIndexNode regionEqualsNode) {
        int fromByteIndexA = fromIndex1 << encoding.getStride();
        int fromByteIndexB = fromIndex2 << encoding.getStride();
        int byteLength = length << encoding.getStride();
        return input.byteLength(encoding.getTStringEncoding()) >= fromByteIndexA + byteLength &&
                        regionEqualsNode.execute(input, fromByteIndexA, match, fromByteIndexB, byteLength, encoding.getTStringEncoding());
    }

    @Specialization(guards = "mask != null")
    public boolean doTStringMask(TruffleString input, int fromIndex1, @SuppressWarnings("unused") TruffleString match, int fromIndex2, int length, TruffleString.WithMask mask, Encoding encoding,
                    @Cached TruffleString.RegionEqualByteIndexNode regionEqualsNode) {
        int fromByteIndexA = fromIndex1 << encoding.getStride();
        int fromByteIndexB = fromIndex2 << encoding.getStride();
        int byteLength = length << encoding.getStride();
        return input.byteLength(encoding.getTStringEncoding()) >= fromByteIndexA + byteLength &&
                        regionEqualsNode.execute(input, fromByteIndexA, mask, fromByteIndexB, byteLength, encoding.getTStringEncoding());
    }
}
