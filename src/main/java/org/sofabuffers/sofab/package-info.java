/*
 * SofaBuffers Java - core stream library.
 *
 * SPDX-License-Identifier: MIT
 */

/**
 * SofaBuffers (<i>Sofab</i>) core stream library for Java — a dependency-free,
 * allocation-light, streaming implementation of the SofaBuffers serialization
 * format.
 *
 * <p>This package is the <b>runtime stream core</b> (equivalent to the C
 * {@code corelib}'s {@code istream} / {@code ostream}), meant to be driven by
 * generated code: a schema-driven generator emits one class per message plus
 * marshal / unmarshal methods that call the primitives here, the same way
 * protobuf-java's generated code calls {@code CodedOutputStream} /
 * {@code CodedInputStream}.
 *
 * <ul>
 *   <li>{@link org.sofabuffers.sofab.OStream} — streaming encoder writing into a
 *       caller buffer, with an optional {@link org.sofabuffers.sofab.FlushSink}.</li>
 *   <li>{@link org.sofabuffers.sofab.IStream} — streaming decoder pushing fields
 *       to a {@link org.sofabuffers.sofab.Visitor} (visitor pattern).</li>
 *   <li>{@link org.sofabuffers.sofab.Sofab} — library constants, including
 *       {@link org.sofabuffers.sofab.Sofab#API_VERSION}.</li>
 * </ul>
 *
 * <p>The wire format is specified, language-neutrally, in the
 * <a href="https://github.com/sofa-buffers/documentation">SofaBuffers
 * documentation</a>. The unit tests use the exact byte vectors from the
 * <a href="https://github.com/sofa-buffers/corelib-c-cpp">C corelib</a>'s
 * reference suite to guarantee byte-for-byte interoperability with the C, C++,
 * Rust and Go implementations.
 */
package org.sofabuffers.sofab;
