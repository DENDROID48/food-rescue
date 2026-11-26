# Browser tests

These scripts drive the real web page in headless Chromium (Playwright) and check it with axe-core for WCAG A/AA problems.

## Running them

1. Start the app with fresh demo data, from the project folder: `mvn spring-boot:run`.
2. In this folder, run:

```bash
npm install          # once: Playwright and axe-core
npx playwright install chromium   # once, if Chromium is missing
npm run e2e          # sign-in, customer, store owner, admin, 4 screen widths, resilience
npm run drops        # Autopilot set-up sheet, Rescue Drops, drop alerts
npm run radar        # Rescue Radar picks, favourites, quiet hours
npm run insights     # Smart Analytics: chart, forecast, CSV export
npm run photos       # photo upload saved as a file, distances
npm run ai-impact    # photo suggestions (needs internet for the image model), My impact page
SP_LOG=../app.log npm run account   # accounts: forgot password, settings, export, deletion (see below)
npm run i18n         # every language: no English left on any role's screens, choosing a language
npm run people       # missed pickups (late@example.com) and blocking accounts from Admin → People
```

`account` reads the password reset link from the app's log (the dev profile writes it there instead
of emailing it), so start the app with its output in a file, for example
`mvn spring-boot:run > app.log`, and pass that file in `SP_LOG`.

The tests run with the page's Content Security Policy switched on, so a script or connection it
blocks shows up as a console error. That is why they use `page.evaluate(...)` rather than
`addScriptTag` to load axe-core.

Each script prints `PASS` or `FAIL` per check and saves screenshots in `screenshots/`.

## Things to know

- The scripts change the demo data, so restart the app before each script.
- Run `drops` during the day. Late in the evening, the app correctly refuses pickup windows that no longer fit before midnight.
