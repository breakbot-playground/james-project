/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.imap.processor;

import static org.apache.james.util.ReactorUtils.logOnError;

import org.apache.james.imap.api.display.HumanReadableText;
import org.apache.james.imap.api.message.response.StatusResponseFactory;
import org.apache.james.imap.api.process.ImapSession;
import org.apache.james.imap.main.PathConverter;
import org.apache.james.imap.message.request.CreateRequest;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.MailboxExistsException;
import org.apache.james.mailbox.exception.TooLongMailboxNameException;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

public class CreateProcessor extends AbstractMailboxProcessor<CreateRequest> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateProcessor.class);

    public CreateProcessor(MailboxManager mailboxManager, StatusResponseFactory factory,
            MetricFactory metricFactory) {
        super(CreateRequest.class, mailboxManager, factory, metricFactory);
    }

    @Override
    protected Mono<Void> processRequestReactive(CreateRequest request, ImapSession session, Responder responder) {
        MailboxManager mailboxManager = getMailboxManager();

        return Mono.fromCallable(() -> PathConverter.forSession(session).buildFullPath(request.getMailboxName()))
            .flatMap(mailboxPath -> Mono.from(mailboxManager.createMailboxReactive(mailboxPath, session.getMailboxSession()))
            .then(unsolicitedResponses(session, responder, false))
            .then(Mono.fromRunnable(() -> okComplete(request, responder)))
            .doOnEach(logOnError(MailboxExistsException.class, e -> LOGGER.debug("Create failed for mailbox {} as it already exists", mailboxPath, e)))
            .onErrorResume(MailboxExistsException.class, e -> {
                no(request, responder, HumanReadableText.MAILBOX_EXISTS);
                return Mono.empty();
            })
            .doOnEach(logOnError(TooLongMailboxNameException.class, e -> LOGGER.debug("The mailbox name length is over limit: {}", mailboxPath.getName(), e)))
            .onErrorResume(TooLongMailboxNameException.class, e -> {
                taggedBad(request, responder, HumanReadableText.FAILURE_MAILBOX_NAME);
                return Mono.empty();
            })
            .doOnEach(logOnError(MailboxException.class, e -> LOGGER.error("Create failed for mailbox {}", mailboxPath, e)))
            .onErrorResume(TooLongMailboxNameException.class, e -> {
                no(request, responder, HumanReadableText.GENERIC_FAILURE_DURING_PROCESSING);
                return Mono.empty();
            })
            .then());
    }

    @Override
    protected MDCBuilder mdc(CreateRequest request) {
        return MDCBuilder.create()
            .addToContext(MDCBuilder.ACTION, "CREATE")
            .addToContext("mailbox", request.getMailboxName());
    }
}
