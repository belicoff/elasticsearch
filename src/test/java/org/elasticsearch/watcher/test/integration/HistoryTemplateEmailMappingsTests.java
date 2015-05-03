/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.test.integration;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.watcher.actions.email.service.EmailTemplate;
import org.elasticsearch.watcher.actions.email.service.support.EmailServer;
import org.elasticsearch.watcher.history.HistoryStore;
import org.elasticsearch.watcher.history.WatchRecord;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTests;
import org.elasticsearch.watcher.transport.actions.put.PutWatchResponse;
import org.junit.After;
import org.junit.Test;

import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.elasticsearch.watcher.actions.ActionBuilders.emailAction;
import static org.elasticsearch.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.watcher.condition.ConditionBuilders.alwaysCondition;
import static org.elasticsearch.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * This test makes sure that the email address fields in the watch_record action result are
 * not analyzed so they can be used in aggregations
 */
public class HistoryTemplateEmailMappingsTests extends AbstractWatcherIntegrationTests {

    static final String USERNAME = "_user";
    static final String PASSWORD = "_passwd";

    private EmailServer server;


    @After
    public void cleanup() throws Exception {
        server.stop();
    }

    @Override
    protected boolean timeWarped() {
        return true; // just to have better control over the triggers
    }

    @Override
    protected boolean enableShield() {
        return false; // remove shield noise from this test
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        if(server == null) {
            //Need to construct the Email Server here as this happens before init()
            server = EmailServer.localhost("2500-2600", USERNAME, PASSWORD, logger);
        }
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("script.disable_dynamic", false)

                // email
                .put("watcher.actions.email.service.account.test.smtp.auth", true)
                .put("watcher.actions.email.service.account.test.smtp.user", USERNAME)
                .put("watcher.actions.email.service.account.test.smtp.password", PASSWORD)
                .put("watcher.actions.email.service.account.test.smtp.port", server.port())
                .put("watcher.actions.email.service.account.test.smtp.host", "localhost")

                .build();
    }

    @Test
    public void testEmailFields() throws Exception {
        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id").setSource(watchBuilder()
                .trigger(schedule(interval("5s")))
                .input(simpleInput())
                .condition(alwaysCondition())
                .addAction("_email", emailAction(EmailTemplate.builder()
                        .from("from@example.com")
                        .to("to1@example.com", "to2@example.com")
                        .cc("cc1@example.com", "cc2@example.com")
                        .bcc("bcc1@example.com", "bcc2@example.com")
                        .replyTo("rt1@example.com", "rt2@example.com")
                        .subject("_subject")
                        .textBody("_body"))))
                .get();

        assertThat(putWatchResponse.isCreated(), is(true));
        timeWarp().scheduler().trigger("_id");
        flush();
        refresh();

        // the action should fail as no email server is available
        assertWatchWithMinimumActionsCount("_id", WatchRecord.State.EXECUTED, 1);

        SearchResponse response = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*").setSource(searchSource()
                .aggregation(terms("from").field("watch_execution.actions_results._email.email.email.from"))
                .aggregation(terms("to").field("watch_execution.actions_results._email.email.email.to"))
                .aggregation(terms("cc").field("watch_execution.actions_results._email.email.email.cc"))
                .aggregation(terms("bcc").field("watch_execution.actions_results._email.email.email.bcc"))
                .aggregation(terms("reply_to").field("watch_execution.actions_results._email.email.email.reply_to"))
                .buildAsBytes())
                .get();

        assertThat(response, notNullValue());
        assertThat(response.getHits().getTotalHits(), is(1L));
        Aggregations aggs = response.getAggregations();
        assertThat(aggs, notNullValue());

        Terms terms = aggs.get("from");
        assertThat(terms, notNullValue());
        assertThat(terms.getBuckets().size(), is(1));
        assertThat(terms.getBucketByKey("from@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("from@example.com").getDocCount(), is(1L));

        terms = aggs.get("to");
        assertThat(terms, notNullValue());
        assertThat(terms.getBuckets().size(), is(2));
        assertThat(terms.getBucketByKey("to1@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("to1@example.com").getDocCount(), is(1L));
        assertThat(terms.getBucketByKey("to2@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("to2@example.com").getDocCount(), is(1L));

        terms = aggs.get("cc");
        assertThat(terms, notNullValue());
        assertThat(terms.getBuckets().size(), is(2));
        assertThat(terms.getBucketByKey("cc1@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("cc1@example.com").getDocCount(), is(1L));
        assertThat(terms.getBucketByKey("cc2@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("cc2@example.com").getDocCount(), is(1L));

        terms = aggs.get("bcc");
        assertThat(terms, notNullValue());
        assertThat(terms.getBuckets().size(), is(2));
        assertThat(terms.getBucketByKey("bcc1@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("bcc1@example.com").getDocCount(), is(1L));
        assertThat(terms.getBucketByKey("bcc2@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("bcc2@example.com").getDocCount(), is(1L));

        terms = aggs.get("reply_to");
        assertThat(terms, notNullValue());
        assertThat(terms.getBuckets().size(), is(2));
        assertThat(terms.getBucketByKey("rt1@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("rt1@example.com").getDocCount(), is(1L));
        assertThat(terms.getBucketByKey("rt2@example.com"), notNullValue());
        assertThat(terms.getBucketByKey("rt2@example.com").getDocCount(), is(1L));
    }
}
