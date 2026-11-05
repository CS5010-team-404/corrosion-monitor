import { useState } from "react";
import { devices, equipmentStatus, profiles, registerDevice, scenarios, setScenario, simulator, users } from "../api/endpoints";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Badge, Button, ErrorState, Modal, PageHead, PageSkeleton, Tabs } from "../components/ui";
import { ago, label } from "../lib/format";
import { useRouter } from "../lib/router";
import { useToast } from "../lib/toast";
import { useAsync } from "../lib/useAsync";
import { sensorsHealth } from "../api/endpoints";

type Tab = "simulator" | "sensors" | "profiles" | "users";

export function Admin() {
  const { path, navigate } = useRouter();
  const tab = (new URLSearchParams(path.split("?")[1] ?? "").get("tab") as Tab) || "simulator";
  return (
    <>
      <PageHead title="Administration" sub="Devices, thresholds, users and the demo simulator." />
      <Tabs value={tab} onChange={(t) => navigate(`/admin?tab=${t}`)} items={[{ key: "simulator", label: "Simulator" }, { key: "sensors", label: "Sensors" }, { key: "profiles", label: "Equipment profiles" }, { key: "users", label: "Users" }]} />
      {tab === "simulator" && <SimulatorTab />}
      {tab === "sensors" && <SensorsTab />}
      {tab === "profiles" && <ProfilesTab />}
      {tab === "users" && <UsersTab />}
    </>
  );
}

function SimulatorTab() {
  const toast = useToast();
  const sim = useAsync(simulator, [], 5000);
  const sc = useAsync(scenarios, []);
  const dev = useAsync(devices, []);
  const hl = useAsync(sensorsHealth, [], 5000);
  if (sim.loading || sc.loading) return <PageSkeleton table />;
  if (sim.error) return <ErrorState error={sim.error} onRetry={sim.reload} />;
  const opts = (sc.data ?? []).map((s) => ({ value: s.name, label: label(s.name), description: s.description }));
  return (
    <>
      <div className="callout" style={{ marginBottom: 20 }}><Icon name="info" /><div>Switch a simulated sensor into a failure to demonstrate detection, alerts and recovery during the demo.</div></div>
      <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>Device</th><th>Equipment</th><th style={{ width: 300 }}>Scenario</th><th>Health</th></tr></thead><tbody>
        {(sim.data ?? []).map((s) => {
          const d = dev.data?.find((x) => x.deviceId === s.deviceId);
          const h = hl.data?.find((x) => x.deviceId === s.deviceId);
          return (
            <tr key={s.deviceId}>
              <td className="cell-main">{s.deviceId}</td>
              <td><div>{d?.equipmentName ?? "–"}</div><div className="cell-sub">{d?.locationName}</div></td>
              <td><Dropdown block value={s.scenario} options={opts} onChange={async (v) => { try { await setScenario(s.deviceId, v); toast(`${s.deviceId} set to ${label(v).toLowerCase()}`); sim.reload(); } catch (e) { toast((e as Error).message, true); } }} /></td>
              <td>{h ? <Badge value={h.status} /> : "–"}</td>
            </tr>
          );
        })}
      </tbody></table></div></div>
    </>
  );
}

function SensorsTab() {
  const toast = useToast();
  const dev = useAsync(devices, []);
  const eq = useAsync(equipmentStatus, []);
  const hl = useAsync(sensorsHealth, [], 10000);
  const [open, setOpen] = useState(false);
  const [deviceId, setDeviceId] = useState("PI-001");
  const [eqId, setEqId] = useState("1");
  const [kind, setKind] = useState("PHYSICAL");
  const [busy, setBusy] = useState(false);
  const [secret, setSecret] = useState<string | null>(null);
  const close = () => { setOpen(false); setSecret(null); };
  const submit = async () => {
    const e = eq.data?.find((x) => String(x.equipmentId) === eqId);
    setBusy(true);
    try {
      const r = await registerDevice({ deviceId, equipmentId: +eqId, equipmentName: e?.equipmentName ?? "", locationName: e?.locationName ?? "", kind });
      setSecret(r.secret); toast(`${deviceId} registered`); dev.reload();
    } catch (err) { toast((err as Error).message, true); } finally { setBusy(false); }
  };
  if (dev.loading) return <PageSkeleton table />;
  return (
    <>
      <div className="row between" style={{ marginBottom: 16 }}><span className="muted">{(dev.data ?? []).length} registered devices</span><Button variant="primary" onClick={() => setOpen(true)}><Icon name="plus" />Register device</Button></div>
      <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>Device</th><th>Kind</th><th>Equipment</th><th className="r">Interval</th><th>Health</th></tr></thead><tbody>
        {(dev.data ?? []).map((d) => { const h = hl.data?.find((x) => x.deviceId === d.deviceId); return (
          <tr key={d.id}><td className="cell-main">{d.deviceId}</td><td>{label(d.kind)} {d.model}</td><td>{d.equipmentName}</td><td className="r num">{d.expectedIntervalS} s</td><td>{h ? <Badge value={h.status} /> : "–"}</td></tr>); })}
      </tbody></table></div></div>
      {open && (
        <Modal title="Register a device" sub="The device key is shown once. Store it on the device." onClose={close}
          footer={secret ? <Button variant="primary" onClick={close}>Done</Button> : <><Button variant="ghost" onClick={close}>Cancel</Button><Button variant="primary" busy={busy} onClick={submit}>Register device</Button></>}>
          {secret ? <><div className="callout ok"><Icon name="check" /><div>Registered <b>{deviceId}</b>. Copy this key now.</div></div><div className="secret">{secret}</div></> : <>
            <label className="field">Device ID<input value={deviceId} onChange={(e) => setDeviceId(e.target.value)} /></label>
            <div className="field">Equipment<Dropdown block value={eqId} onChange={setEqId} options={(eq.data ?? []).map((e) => ({ value: String(e.equipmentId), label: e.equipmentName, description: e.locationName }))} /></div>
            <div className="field">Kind<Dropdown block value={kind} onChange={setKind} options={[{ value: "PHYSICAL", label: "Physical", description: "Raspberry Pi + SHT31" }, { value: "SIMULATED", label: "Simulated" }]} /></div>
          </>}
        </Modal>
      )}
    </>
  );
}

function ProfilesTab() {
  const p = useAsync(profiles, []);
  if (p.loading) return <PageSkeleton table />;
  if (p.error) return <ErrorState error={p.error} onRetry={p.reload} />;
  return (
    <>
      <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>Profile</th><th className="r">RH warning</th><th className="r">RH critical</th><th className="r">Margin warning</th><th className="r">Margin critical</th><th className="r">Temperature</th></tr></thead><tbody>
        {(p.data ?? []).map((x) => <tr key={x.id}><td className="cell-main">{x.name}</td><td className="r num">{x.rhWarningPct}%</td><td className="r num">{x.rhCriticalPct}%</td><td className="r num">{x.marginWarningC} °C</td><td className="r num">{x.marginCriticalC} °C</td><td className="r num">{x.tempMinC} to {x.tempMaxC} °C</td></tr>)}
      </tbody></table></div></div>
      <p className="small muted" style={{ marginTop: 14 }}>IT equipment limits follow ASHRAE TC 9.9 (5th edition, 2021): RH up to 70% with low pollutants, 50% where corrosive gases are present.</p>
    </>
  );
}

function UsersTab() {
  const u = useAsync(users, []);
  const toast = useToast();
  if (u.loading) return <PageSkeleton table />;
  if (u.error) return <ErrorState error={u.error} onRetry={u.reload} />;
  return (
    <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>User</th><th style={{ width: 220 }}>Role</th><th>Last sign-in</th><th>Status</th></tr></thead><tbody>
      {(u.data ?? []).map((x) => (
        <tr key={x.id}><td><div className="row" style={{ gap: 12, flexWrap: "nowrap" }}><span className="avatar sm">{x.fullName[0]}</span><div><div className="cell-main">{x.fullName}</div><div className="cell-sub">{x.username}</div></div></div></td>
          <td><Dropdown block value={x.role} onChange={() => toast("Role changes are saved by the team backend (admin API)")} options={["TECHNICIAN", "OPERATOR", "MANAGER", "ADMIN"].map((r) => ({ value: r, label: label(r) }))} /></td>
          <td className="num small">{ago(x.lastLoginAt)}</td><td>{x.lockedUntil ? <Badge value="CRITICAL" text="Locked" /> : x.enabled ? <Badge value="HEALTHY" text="Active" /> : <Badge text="Disabled" />}</td></tr>
      ))}
    </tbody></table></div></div>
  );
}
