// Shows every <time datetime="...Z"> (UTC from the server) in the time zone of the browser.
// The server never converts a time zone; without this script the pages show the UTC text.
(function () {
  "use strict";
  var format = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "medium" });
  var zone = format.resolvedOptions().timeZone;
  function localizeDates() {
    document.querySelectorAll("[data-date-zone]").forEach(function (field) {
      if (!field.value && zone) { field.value = zone; }
    });
    var times = document.querySelectorAll("time[datetime]");
    for (var i = 0; i < times.length; i++) {
      var time = times[i];
      var utc = time.getAttribute("datetime");
      var instant = new Date(utc);
      if (!isNaN(instant.getTime())) {
        time.title = utc;
        time.textContent = format.format(instant);
      }
    }
  }

  localizeDates();
  document.addEventListener("veridger:page", localizeDates);
})();
