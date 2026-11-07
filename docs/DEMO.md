# Four-minute demo

Before: open the web app on a laptop, sign in as admin in a second window. Everything below is live data.

1. **Operator, overview (30 s).** The dark card shows the equipment closest to condensation, its margin and the
   60-minute forecast. Locations and tiles show all twelve devices.
2. **Admin, simulator (30 s).** Set `SIM-002` (Rack A2) to *Humid night*. Back on the overview, humidity climbs and
   the margin shrinks; the ML early warning fires before the 3 °C rule would.
3. **Technician, alert (60 s).** The bell shows a new alert. Open it, read the timeline and the escalation timer,
   acknowledge, log "Turned on dehumidifier", resolve.
4. **Admin, sensor health (45 s).** Set `SIM-003` to *Stuck*. Sensor health flags it within about five minutes
   (20 identical readings at 15 s), and its tile shows "not trusted": analytics stops using it. Set `SIM-004` to
   *Bad signature*: rejections are counted and flagged.
5. **Manager, reports and audit (45 s).** Generate a report for the last 7 days, export CSV/PDF, then verify the
   audit chain: every action from steps 2-4 is in it.

Backup: if the network fails, run `npm run dev:mock` locally; the same story works on the built-in simulation.
Load-test idea for the "500 readings/s" target: run several `SimulatorMain` processes with 1-second intervals and
watch ingestion latency in the logs.
