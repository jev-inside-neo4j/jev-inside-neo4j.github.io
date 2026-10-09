# Introduction

## What This Book Is

This book follows the building of a small piece of software: a function and a batch procedure for Neo4j, written in Java, that ask a model to make a decision about a node and store the answer back in the graph. We build it one lesson at a time and each lesson comes from a problem we hit or a number we measured.

We don't start from a design and apply it. Each chapter adds the code that the previous chapter's problem called for. A first version fails the whole query when a call fails, so we change it. A configurable address would send a credential to the wrong place, so we add a rule. By the end you'll have seen the function grow from a single call into something you could put in front of a whole graph and you'll know why each part is there.

One example runs through every chapter: 500 synthetic transactions, a question about each one and the decisions stored as nodes linked to the transactions they describe. It's small enough to run on a laptop and varied enough to show where a decision is easy and where it isn't.

## What It Isn't

It isn't a study of how good any model is. We run a hosted service and a small local model (`tev1:0.8b`, served by Ollama) on the same data and we report what happened, but the data are synthetic, the runs are few and the prompt is one we wrote once. The numbers show how the code behaves and give a sense of scale. They aren't a ranking.

It isn't a product manual either. The code is there to teach, so it favors being easy to read over being complete and the book says what it leaves out.

## How to Read It

Read the chapters in order. Each builds on the one before and each uses only what has been built so far, so nothing in a chapter depends on code you haven't seen.

Every chapter that has code has a folder in the repository, with the project as it stands at the end of that chapter. The folders build on their own, so you can start from any of them if you'd rather not follow every step. The JAR has the same name in every folder, which means a newer one simply replaces the older one.

When a chapter gives Cypher to run, run the statements one at a time. Running a whole file at once only tells you that it succeeded and the point of most of the statements is the result they show you.

The output you see in the book is real output from our runs, pasted in. Where a number came from a run that we didn't repeat, the chapter says so. Where something surprised us and we don't know why, the chapter says that too.

## Conventions

We write about the graph in graph terms: nodes and relationships, which some people call edges. A query returns results and where we need a count of what came back, we count those results. The word "row" appears only where we mean one line of a query's output or a line in a CSV file.

We write "we" for the build, because the code and the measurements came out of working through it. Versions of tools and libraries are pinned in the code files and the pom and left out of the text, so the text doesn't go stale when the code is updated.

## Where to Start

Chapter 1 explains why a decision belongs in the database, shows what the finished function returns and tells you what you need to follow along. If you already know that and want to see code, chapter 2 builds the first working function and makes the first call.
