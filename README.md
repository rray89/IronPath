# IronPath

IronPath is a local-first Android workout planning app I built as a portfolio project.

I wanted to make something that felt like a real product, not just a set of polished screens or a tutorial-style app. The goal was to keep the scope small, focus on one useful workout flow, and build an MVP that feels intentional from both a product and engineering perspective.

## What the MVP does
- generate a one-week workout plan
- let the user review and accept that plan
- support the current week through Home / Plan / Active / History
- run an active workout session for today
- save workout history logs
- save simple personal records

## What I wanted to practice
With IronPath, I wanted to practice more than just Android implementation. This project was a way to get better at:
- turning a rough idea into clear product rules
- making solid MVP tradeoffs
- keeping scope under control
- building local-first flows with real persistence and state
- creating something small, coherent, and easy to explain

## Tech stack
- Kotlin
- Jetpack Compose
- Material 3
- Room
- Koin
- Navigation Compose

## Why I kept the scope narrow
A big part of this project was learning to resist unnecessary complexity.

So for MVP, I intentionally left out:
- auth
- backend sync
- AI coaching
- multi-device support
- advanced analytics
- full program-builder complexity

I wanted the first version to feel clean, credible, and complete enough to demo, discuss, and improve over time.

## What I'm happy with
- the app has a clear product shape
- the flows connect end-to-end instead of stopping at static UI
- the architecture stays simple enough to reason about
- the scope feels disciplined, which was one of the main goals of the project

## What I'd improve next
- smarter plan generation
- stronger active-session UX
- records derived from completed workouts
- more testing and edge-case hardening
- another pass on polish and interaction details

## Status
IronPath is intentionally still an MVP. That is part of the point.

I built it to show how I think about product scope, user flow, Android architecture, and iterative improvement — not to pretend I built a full production fitness platform in one pass.
