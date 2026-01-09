---
description: Tasking the agent with creating the topology, processor, and comparator for implementing the resequencer pattern within the sample-app module so we can test it before extracting the logic to the library module.
version: v1
changes: None
---

We want to update the @sample-app module KStream processor. Within the existing topology, we want to resequence the data  using the EIP Resequencer Pattern (See https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html ).

When we are done, the data in our sink topic will be in a different order than the data in our input topic. You can modify the topology however you see fit, but it should still end with mapping to the new "sorted" key/value then immediately producing to the sorted sink topic.

This is a collaborative process. Do not make assumptions. During planning and while coding, make a list of assumptions you have, and ask for confirmation about those assumptions. Ask questions for clarification about anything you are unsure of. 

This might mean we need to implement an instance of `org.apache.kafka.streams.processor.api.Processor` or maybe a `org.apache.kafka.streams.kstream.ValueJoiner`.  In the planning phase you'll need to consider how to best do this.  This re-sequencing needs to happen before the `.map(..)` and the keys and records that comes out of our re-sequence process should be exactly the same as the keys and records that came into the process - just in a different order. 

It is highly likely that we'll need a state store for this. It does not need to be persistent. This is a sample app, so if needed, use whatever is easiest to do with minimal configuration.  Ideally this is done in a way that makes it easy to change or swap out the state store implementation later with minimal changes to the topology.

Given the nature of this task, I am pretty certain that windowing will be needed.  We'll want to buffer all records for a given key and only publish once we are confident we have an order we are confident in. We must make sure to be in full control of emitting events, so records only make it to the sink when we have sequenced them (e.g. suppress events).  The type of windowing to use will be difficult to determine.  Ideally we'd use a Session windows, but the problem with session windows is they only emit when the window expires.  If we have a long running session, this might cause an issue.  For now, le't just use a session window and consider the long running session a tech debt issue (as well as sparse topic partitions like batch jobs which will also not close the session)

You can make whatever domain models you need to handle this complex buffering and windowing logic.  You can add as many data stores as you need.  You can even create multiple KStreams if that helps.

The sequencing logic should be encapsulated so the user can make modification without changing the topology or the buffering processor.  This will likely be done in a comparator that will have to be supplied to the re-sequencing processor.

For our initial implementation, we will  `operationType` type first: CREATE > UPDATE > DELETE, then use the `timestamp` as the fallback comparison.  The buffering logic that calls the comparison logic should also have one more fallback if comparing 2 records comes back as "equal", then it should use Kafka header details to make the final determiniation: if they were on the same partition, then lowest offset is first, if they were on different partitions, lowest kafka timestamp wins.

When done, we should be able to remove the `@PendingFeature` annotation from the @OutOfOrderSpec.groovy test and have it pass.

Again, this is a collaboration. Do not make assumptions. Ask for clarification.

