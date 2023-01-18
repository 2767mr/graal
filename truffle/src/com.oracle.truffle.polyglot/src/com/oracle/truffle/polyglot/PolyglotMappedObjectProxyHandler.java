/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.polyglot;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.polyglot.PolyglotLanguageContext.ToGuestValuesNode;
import com.oracle.truffle.polyglot.PolyglotMappedObjectProxyHandlerFactory.MappedObjectProxyNodeGen;
import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicMapWrap;
import org.graalvm.collections.UnmodifiableEconomicMap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class PolyglotMappedObjectProxyHandler implements InvocationHandler, PolyglotWrapper {

    static final Object[] EMPTY = {};
    static final MappedObjectProxyComperator comperator = new MappedObjectProxyComperator();

    final Object obj;
    final PolyglotLanguageContext languageContext;
    final CallTarget invoke;

    PolyglotMappedObjectProxyHandler(Object obj, PolyglotLanguageContext languageContext, Class<?> interfaceClass, Map<String, String> executables, Map<String, String> instantiables, Map<String, String> fields) {
        this.obj = obj;
        this.languageContext = languageContext;
        this.invoke = MappedObjectProxyNode.lookup(languageContext, obj.getClass(), interfaceClass, new EconomicMapWrap<>(executables), new EconomicMapWrap<>(instantiables), new EconomicMapWrap<>(fields));
    }

    @Override
    public Object getGuestObject() {
        return obj;
    }

    @Override
    public PolyglotLanguageContext getLanguageContext() {
        return languageContext;
    }

    @Override
    public PolyglotContextImpl getContext() {
        return languageContext.context;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        CompilerAsserts.neverPartOfCompilation();
        Object[] resolvedArguments = arguments == null ? EMPTY : arguments;
        try {
            return invoke.call(languageContext, obj, method, resolvedArguments);
        } catch (UnsupportedOperationException e) {
            try {
                //TODO: mapping logic
                return PolyglotFunctionProxyHandler.invokeDefault(this, proxy, method, resolvedArguments);
            } catch (Exception innerE) {
                e.addSuppressed(innerE);
                throw e;
            }
        }
    }

    @TruffleBoundary
    static Object newProxyInstance(Class<?> clazz, Object obj, PolyglotLanguageContext languageContext, Map<String, String> executables, Map<String, String> instantiables, Map<String, String> fields) throws IllegalArgumentException {
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz}, new PolyglotMappedObjectProxyHandler(obj, languageContext, clazz, executables, instantiables, fields));
    }

    static final class MappedObjectProxyComperator {
        Class<?> receiverClass;
        Class<?> interfaceType;

        public void setTypes(Class<?> receiverClass, Class<?> interfaceType) {
            this.receiverClass = receiverClass;
            this.interfaceType = interfaceType;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Objects.hashCode(receiverClass);
            result = 31 * result + Objects.hashCode(interfaceType);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MappedObjectProxyNode) {
                MappedObjectProxyNode other = (MappedObjectProxyNode) obj;
                return receiverClass == other.receiverClass && interfaceType == other.interfaceType;
            } else if (obj instanceof MappedObjectProxyComperator) {
                MappedObjectProxyComperator other = (MappedObjectProxyComperator) obj;
                return receiverClass == other.receiverClass && interfaceType == other.interfaceType;
            } else {
                return false;
            }
        }
    }

    abstract static class MappedObjectProxyNode extends HostToGuestRootNode {

        final Class<?> receiverClass;
        final Class<?> interfaceType;

        final UnmodifiableEconomicMap<String, String> executables;
        final UnmodifiableEconomicMap<String, String> instantiables;
        final UnmodifiableEconomicMap<String, String> fields;


        MappedObjectProxyNode(PolyglotLanguageInstance languageInstance, Class<?> receiverType, Class<?> interfaceType, UnmodifiableEconomicMap<String, String> executables, UnmodifiableEconomicMap<String, String> instantiables, UnmodifiableEconomicMap<String, String> fields) {
            super(languageInstance);
            this.receiverClass = receiverType;
            this.interfaceType = interfaceType;

            this.executables = executables;
            this.instantiables = instantiables;
            this.fields = fields;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Class<? extends TruffleObject> getReceiverType() {
            return (Class<? extends TruffleObject>) receiverClass;
        }

        @Override
        public String getName() {
            return "InterfaceProxy<" + receiverClass + ">";
        }

        @Specialization
        static Object doDefault(PolyglotLanguageContext languageContext, Object receiver, Object[] args,
                                @Bind("this") Node node,
                                @Cached("create(executables, instantiables, fields)") MappedProxyInvokeNode proxyInvoke,
                                @Cached ToGuestValuesNode toGuests) {
            Method method = (Method) args[ARGUMENT_OFFSET];
            Object[] arguments = toGuests.execute(node, languageContext, (Object[]) args[ARGUMENT_OFFSET + 1]);
            return proxyInvoke.execute(languageContext, receiver, method, arguments);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Objects.hashCode(receiverClass);
            result = 31 * result + Objects.hashCode(interfaceType);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MappedObjectProxyNode) {
                MappedObjectProxyNode other = (MappedObjectProxyNode) obj;
                return receiverClass == other.receiverClass && interfaceType == other.interfaceType;
            } else if (obj instanceof MappedObjectProxyComperator) {
                MappedObjectProxyComperator other = (MappedObjectProxyComperator) obj;
                return receiverClass == other.receiverClass && interfaceType == other.interfaceType;
            } else {
                return false;
            }
        }

        static CallTarget lookup(PolyglotLanguageContext languageContext, Class<?> receiverClass, Class<?> interfaceClass, UnmodifiableEconomicMap<String, String> executables, UnmodifiableEconomicMap<String, String> instantiables, UnmodifiableEconomicMap<String, String> fields) {
            comperator.setTypes(receiverClass, interfaceClass);
            CallTarget target = lookupHostCodeCache(languageContext, comperator, CallTarget.class);
            if (target == null) {
                MappedObjectProxyNode node = MappedObjectProxyNodeGen.create(languageContext.getLanguageInstance(), receiverClass, interfaceClass, executables, instantiables, fields);
                target = installHostCodeCache(languageContext, node, node.getCallTarget(), CallTarget.class);
            }
            return target;
        }
    }

    abstract static class MappedProxyInvokeNode extends Node {
        final UnmodifiableEconomicMap<String, String> executables;
        final UnmodifiableEconomicMap<String, String> instantiables;
        final UnmodifiableEconomicMap<String, String> fields;

        MappedProxyInvokeNode(UnmodifiableEconomicMap<String, String> executables, UnmodifiableEconomicMap<String, String> instantiables, UnmodifiableEconomicMap<String, String> fields) {
            super();
            this.executables = executables;
            this.instantiables = instantiables;
            this.fields = fields;
        }

        public abstract Object execute(PolyglotLanguageContext languageContext, Object receiver, Method method, Object[] arguments);

        /*
         * The limit of the proxy node is unbounded. There are only so many methods a Java interface
         * can have. So we always want to specialize.
         */
        protected static final int LIMIT = Integer.MAX_VALUE;

        @CompilationFinal
        private boolean invokeFailed;

        /*
         * It is supposed to be safe to compare method names with == only as they are always
         * interned.
         */
        @Specialization(guards = {"cachedMethod == method", "executables.containsKey(cachedMethodName)"}, limit = "LIMIT")
        @SuppressWarnings({"unused", "truffle-static-method"})
        protected Object doCachedMethod(PolyglotLanguageContext languageContext, Object receiver, Method method, Object[] arguments,
                                        @Bind("this") Node node,
                                        @Cached("method") Method cachedMethod,
                                        @Cached("method.getName()") String cachedMethodName,
                                        @Cached("executables.get(cachedMethodName)") String name,
                                        @Cached("getMethodReturnType(method)") Class<?> returnClass,
                                        @Cached("getMethodGenericReturnType(method)") Type returnType,
                                        @Exclusive @CachedLibrary("receiver") InteropLibrary receivers,
                                        @Shared("cachedMembersLib") @CachedLibrary(limit = "LIMIT") InteropLibrary members,
                                        @Exclusive @Cached PolyglotToHostNode toHost,
                                        @Exclusive @Cached InlinedBranchProfile error) {
            Object result = invoke(node, languageContext, receiver, arguments, name, receivers, members, error);
            return toHost.execute(node, languageContext, result, returnClass, returnType);
        }

        @Specialization(guards = {"cachedMethod == method", "fields.containsKey(cachedMethodName)"}, limit = "LIMIT")
        @SuppressWarnings({"unused", "truffle-static-method"})
        protected Object doCachedField(PolyglotLanguageContext languageContext, Object receiver, Method method, Object[] arguments,
                                       @Bind("this") Node node,
                                       @Cached("method") Method cachedMethod,
                                       @Cached("method.getName()") String cachedMethodName,
                                       @Cached("fields.get(cachedMethodName)") String name,
                                       @Cached("getMethodReturnType(method)") Class<?> returnClass,
                                       @Cached("getMethodGenericReturnType(method)") Type returnType,
                                       @Exclusive @CachedLibrary("receiver") InteropLibrary receivers,
                                       @Exclusive @Cached PolyglotToHostNode toHost,
                                       @Exclusive @Cached InlinedBranchProfile error) {
            Object result = getField(node, languageContext, receiver, arguments, name, receivers, error);
            return toHost.execute(node, languageContext, result, returnClass, returnType);
        }

        @Specialization(guards = {"cachedMethod == method", "instantiables.containsKey(cachedMethodName)"}, limit = "LIMIT")
        @SuppressWarnings({"unused", "truffle-static-method"})
        protected Object doCachedInstanciable(PolyglotLanguageContext languageContext, Object receiver, Method method, Object[] arguments,
                                              @Bind("this") Node node,
                                              @Cached("method") Method cachedMethod,
                                              @Cached("method.getName()") String cachedMethodName,
                                              @Cached("instantiables.get(cachedMethodName)") String name,
                                              @Cached("getMethodReturnType(method)") Class<?> returnClass,
                                              @Cached("getMethodGenericReturnType(method)") Type returnType,
                                              @Exclusive @CachedLibrary("receiver") InteropLibrary receivers,
                                              @Shared("cachedMembersLib") @CachedLibrary(limit = "LIMIT") InteropLibrary members,
                                              @Exclusive @Cached PolyglotToHostNode toHost,
                                              @Exclusive @Cached InlinedBranchProfile error) {
            Object result = instantiate(node, languageContext, receiver, arguments, name, receivers, members, error);
            return toHost.execute(node, languageContext, result, returnClass, returnType);
        }

        @Specialization(guards = {"cachedMethod == method", "!executables.containsKey(name)", "!fields.containsKey(name)", "!instantiables.containsKey(name)"}, limit = "LIMIT")
        @SuppressWarnings({"truffle-static-method"})
        protected Object unhandled(PolyglotLanguageContext languageContext, Object receiver, Method method, Object[] arguments,
                                   @Bind("this") Node node,
                                   @Cached("method") Method cachedMethod,
                                   @Cached("method.getName()") String name,
                                   @Cached("getMethodReturnType(method)") Class<?> returnClass,
                                   @Cached("getMethodGenericReturnType(method)") Type returnType,
                                   @Exclusive @CachedLibrary("receiver") InteropLibrary receivers,
                                   @Shared("cachedMembersLib") @CachedLibrary(limit = "LIMIT") InteropLibrary members,
                                   @Cached InlinedConditionProfile branchProfile,
                                   @Exclusive @Cached  PolyglotToHostNode toHost,
                                   @Exclusive @Cached InlinedBranchProfile error) {
            Object result = invokeOrExecute(node, languageContext, receiver, arguments, name, receivers, members, branchProfile, error);
            return toHost.execute(node, languageContext, result, returnClass, returnType);
        }

        static Class<?> getMethodReturnType(Method method) {
            if (method == null || method.getReturnType() == void.class) {
                return Object.class;
            }
            return method.getReturnType();
        }

        static Type getMethodGenericReturnType(Method method) {
            if (method == null || method.getReturnType() == void.class) {
                return Object.class;
            }
            return method.getGenericReturnType();
        }

        private Object invokeOrExecute(Node node, PolyglotLanguageContext polyglotContext, Object receiver, Object[] arguments, String member, InteropLibrary receivers,
                                       InteropLibrary members,
                                       InlinedConditionProfile invokeProfile, InlinedBranchProfile error) {
            try {
                boolean localInvokeFailed = this.invokeFailed;
                if (!localInvokeFailed) {
                    try {
                        return receivers.invokeMember(receiver, member, arguments);
                    } catch (UnsupportedMessageException | UnknownIdentifierException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        // fallthrough to unsupported
                        invokeFailed = localInvokeFailed = true;
                    }
                }
                if (localInvokeFailed) {
                    if (invokeProfile.profile(node, receivers.isMemberInvocable(receiver, member))) {
                        return receivers.invokeMember(receiver, member, arguments);
                    } else if (receivers.isMemberReadable(receiver, member)) {
                        Object readMember = receivers.readMember(receiver, member);
                        if (members.isExecutable(readMember)) {
                            return members.execute(readMember, arguments);
                        } else if (arguments.length == 0) {
                            return readMember;
                        }
                    }
                }
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            } catch (UnknownIdentifierException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            } catch (UnsupportedTypeException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invalidExecuteArgumentType(polyglotContext, receiver, e.getSuppliedValues());
            } catch (ArityException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invalidExecuteArity(polyglotContext, receiver, arguments, e.getExpectedMinArity(), e.getExpectedMaxArity(), e.getActualArity());
            } catch (UnsupportedMessageException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            }
        }


        private Object invoke(Node node, PolyglotLanguageContext polyglotContext, Object receiver, Object[] arguments, String member, InteropLibrary receivers, InteropLibrary members, InlinedBranchProfile error) {
            try {
                if (receivers.isMemberReadable(receiver, member)) {
                    Object readMember = receivers.readMember(receiver, member);
                    if (members.isExecutable(readMember)) {
                        return members.execute(readMember, arguments);
                    }
                }

                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            } catch (UnknownIdentifierException | UnsupportedMessageException | UnsupportedTypeException |
                     ArityException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            }
        }

        private Object getField(Node node, PolyglotLanguageContext polyglotContext, Object receiver, Object[] arguments, String member, InteropLibrary receivers, InlinedBranchProfile error) {
            try {
                if (receivers.isMemberReadable(receiver, member)) {
                    return receivers.readMember(receiver, member);
                }

                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            } catch (UnknownIdentifierException | UnsupportedMessageException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            }
        }

        private Object instantiate(Node node, PolyglotLanguageContext polyglotContext, Object receiver, Object[] arguments, String member, InteropLibrary receivers, InteropLibrary members, InlinedBranchProfile error) {
            try {
                if (receivers.isMemberReadable(receiver, member)) {
                    Object readMember = receivers.readMember(receiver, member);
                    if (members.isInstantiable(readMember)) {
                        return members.instantiate(readMember, arguments);
                    }
                }
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            } catch (UnknownIdentifierException | UnsupportedMessageException | UnsupportedTypeException |
                     ArityException e) {
                error.enter(node);
                throw PolyglotInteropErrors.invokeUnsupported(polyglotContext, receiver, member);
            }
        }
    }
}
