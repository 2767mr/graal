/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.host;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

import static com.oracle.truffle.host.HostTargetProxyMappingNodeGen.SingleMappingNodeGen;

@GenerateUncached
@GenerateInline
@GenerateCached
abstract class HostTargetProxyMappingNode extends Node {
    public static final Object NO_RESULT = new Object();

    abstract Object execute(Node node, Object value, Class<?> targetType, HostContext hostContext, InteropLibrary interop, boolean checkOnly, int startPriority, int endPriority);

    @SuppressWarnings("unused")
    @Specialization(guards = "targetType != null")
    @ExplodeLoop
    protected Object doCached(Object operand, Class<?> targetType, HostContext context, InteropLibrary interop, boolean checkOnly, int startPriority, int endPriority,
                              @Cached(value = "getMappings(context, targetType)", dimensions = 1, neverDefault = true) HostTargetProxyMapping[] mappings,
                              @Cached(value = "createMappingNodes(mappings)", neverDefault = true) SingleMappingNode[] mappingNodes) {
        assert startPriority <= endPriority;
        Object result = NO_RESULT;
        if (mappingNodes != null) {
            for (int i = 0; i < mappingNodes.length; i++) {
                result = mappingNodes[i].execute(operand, mappings[i], context, interop, checkOnly);
                if (result != NO_RESULT) {
                    break;
                }
            }
        }
        return result;
    }

    @Specialization(replaces = "doCached")
    @SuppressWarnings("unused")
    @CompilerDirectives.TruffleBoundary
    protected Object doUncached(Object operand, Class<?> targetType, HostContext hostContext, InteropLibrary interop, boolean checkOnly, int startPriority, int endPriority) {
        assert startPriority <= endPriority;
        Object result = NO_RESULT;
        HostTargetProxyMapping[] mappings = getMappings(hostContext, targetType);
        if (mappings != null) {
            SingleMappingNode uncachedNode = SingleMappingNodeGen.getUncached();
            for (int i = 0; i < mappings.length; i++) {
                result = uncachedNode.execute(operand, mappings[i], hostContext, interop, checkOnly);
                if (result != NO_RESULT) {
                    break;
                }
            }
        }
        return result;
    }

    @CompilerDirectives.TruffleBoundary
    static HostTargetProxyMapping[] getMappings(HostContext hostContext, Class<?> targetType) {
        if (hostContext == null) {
            return HostClassCache.EMPTY_PROXY_MAPPINGS;
        }
        return hostContext.getHostClassCache().getProxyMappings(targetType);
    }

    @CompilerDirectives.TruffleBoundary
    static SingleMappingNode[] createMappingNodes(HostTargetProxyMapping[] mappings) {
        if (mappings == null) {
            return null;
        }
        SingleMappingNode[] nodes = new SingleMappingNode[mappings.length];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = SingleMappingNodeGen.create();
        }
        return nodes;
    }

    static HostTargetProxyMappingNode create() {
        return HostTargetProxyMappingNodeGen.create();
    }

    static HostTargetProxyMappingNode getUncached() {
        return HostTargetProxyMappingNodeGen.getUncached();
    }

    @GenerateUncached
    @SuppressWarnings("unchecked")
    @GenerateInline(false) // cannot be inlined
    abstract static class SingleMappingNode extends Node {

        abstract Object execute(Object receiver, HostTargetProxyMapping targetMapping, HostContext context, InteropLibrary interop, boolean checkOnly);

        @Specialization
        protected Object doDefault(Object receiver, @SuppressWarnings("unused") HostTargetProxyMapping cachedMapping,
                                   HostContext context, InteropLibrary interop, boolean checkOnly,
                                   @Bind("this") Node node,
                                   @Cached(value = "allowsImplementation(context, cachedMapping.from)", allowUncached = true, neverDefault = false) boolean allowsImplementation) {
            if (HostToTypeNode.canConvert(node, receiver, cachedMapping.from, cachedMapping.from,
                    allowsImplementation, context, HostToTypeNode.LOWEST, interop, null)) {
                return context.language.access.toMappedObjectProxy(context.internalContext, cachedMapping.to, receiver, cachedMapping.executables, cachedMapping.instantiables, cachedMapping.fields);
            }
            return NO_RESULT;
        }

        static boolean allowsImplementation(HostContext context, Class<?> type) {
            return HostToTypeNode.allowsImplementation(context, type);
        }
    }
}
