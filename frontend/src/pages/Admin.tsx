import { useState } from "react";
import { devices, equipmentStatus, profiles, registerDevice, scenarios, setScenario, setSensorInterval, setUserRole, simulator, unlockUser, updateProfile, users } from "../api/endpoints";
import type * as T from "../api/types";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Badge, Button, Empty, ErrorState, Modal, PageHead, PageSkeleton, Tabs } from "../components/ui";
import { errorMessage } from "../lib/api";
import { useAuth } from "../lib/auth";
import { ago, label } from "../lib/format";
import { INTERVAL_MAX_S, INTERVAL_MIN_S, intervalLabel, intervalProblem } from "../lib/sensors";
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
  const [editing, setEditing] = useState<T.Device | null>(null);
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
  if (dev.error) return <ErrorState error={dev.error} onRetry={dev.reload} />;
  const list = dev.data ?? [];
  return (
    <>
      <div className="row between" style={{ marginBottom: 16 }}><span className="muted">{list.length} registered devices</span><Button variant="primary" onClick={() => setOpen(true)}><Icon name="plus" />Register device</Button></div>
      {list.length === 0 ? <div className="card"><Empty icon="wifi" title="No devices yet" text="Register a Raspberry Pi or a simulated sensor to start receiving readings." /></div> :
      <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>Device</th><th>Kind</th><th>Equipment</th><th className="r">Reporting interval</th><th>Health</th></tr></thead><tbody>
        {list.map((d) => { const h = hl.data?.find((x) => x.deviceId === d.deviceId); return (
          <tr key={d.id}><td className="cell-main">{d.deviceId}</td><td>{label(d.kind)} {d.model}</td><td>{d.equipmentName}</td>
            <td className="r"><Button size="sm" variant="ghost" aria-label={`Change reporting interval of ${d.deviceId}, now ${intervalLabel(d.expectedIntervalS)}`} onClick={() => setEditing(d)}><span className="num">{intervalLabel(d.expectedIntervalS)}</span><Icon name="clock" /></Button></td>
            <td>{h ? <Badge value={h.status} /> : "–"}</td></tr>); })}
      </tbody></table></div></div>}
      {editing && <IntervalDialog device={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); dev.reload(); }} />}
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

/** Administration > Sensors: change how often one device reports (PATCH /api/v1/admin/sensors/{id}, admins only). */
export function IntervalDialog({ device, onClose, onSaved }: { device: T.Device; onClose: () => void; onSaved: (d: T.Device) => void }) {
  const toast = useToast();
  const [value, setValue] = useState(String(device.expectedIntervalS));
  const [busy, setBusy] = useState(false);
  const problem = intervalProblem(value);
  const unchanged = !problem && Number(value) === device.expectedIntervalS;
  const save = async () => {
    if (problem || unchanged || busy) return;
    setBusy(true);
    try {
      const d = await setSensorInterval(device.id, Number(value));
      toast(`${device.deviceId} now reports every ${intervalLabel(d.expectedIntervalS)}`);
      onSaved(d);
    } catch (err) { toast(errorMessage(err), true); } finally { setBusy(false); }
  };
  const hint = device.kind === "SIMULATED" ? "The simulator switches to it straight away."
    : "Set the device to the same interval, or sensor health will show it as late.";
  return (
    <Modal title={`Reporting interval: ${device.deviceId}`} sub="How often this sensor sends a reading. Sensor health judges late and offline against it." onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!!problem || unchanged} onClick={save}>Save interval</Button></>}>
      <label className="field">Interval in seconds
        <input type="number" inputMode="numeric" min={INTERVAL_MIN_S} max={INTERVAL_MAX_S} step={1} value={value} autoFocus
          aria-invalid={!!problem} onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") void save(); }} />
        {problem ? <span className="field-error" role="alert">{problem}</span>
          : <span className="field-hint">{INTERVAL_MIN_S} to {INTERVAL_MAX_S} s. {hint}</span>}
      </label>
    </Modal>
  );
}

export function ProfilesTab() {
  const p = useAsync(profiles, []);
  const { can } = useAuth();
  const [editing, setEditing] = useState<T.Profile | null>(null);
  const admin = can("ADMIN");
  if (p.loading) return <PageSkeleton table />;
  if (p.error) return <ErrorState error={p.error} onRetry={p.reload} />;
  if (!p.data?.length) return <div className="card"><Empty icon="sliders" title="No equipment profiles" text="Profiles come from the team backend's equipment_profile table." /></div>;
  return (
    <>
      <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>Profile</th><th className="r">RH warning</th><th className="r">RH critical</th><th className="r">Margin warning</th><th className="r">Margin critical</th><th className="r">Temperature</th><th className="r">Surface offset</th>{admin && <th className="r"><span className="sr">Actions</span></th>}</tr></thead><tbody>
        {p.data.map((x) => <tr key={x.id}><td className="cell-main">{x.name}</td><td className="r num">{x.rhWarningPct}%</td><td className="r num">{x.rhCriticalPct}%</td><td className="r num">{x.marginWarningC} °C</td><td className="r num">{x.marginCriticalC} °C</td><td className="r num">{x.tempMinC} to {x.tempMaxC} °C</td><td className="r num">{x.surfaceOffsetC} °C</td>
          {admin && <td className="r"><Button size="sm" variant="ghost" aria-label={`Edit ${x.name}`} onClick={() => setEditing(x)}>Edit</Button></td>}</tr>)}
      </tbody></table></div></div>
      <p className="small muted" style={{ marginTop: 14 }}>IT equipment limits follow ASHRAE TC 9.9 (5th edition, 2021): RH up to 70% with low pollutants, 50% where corrosive gases are present.</p>
      {editing && <ProfileDialog profile={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); p.reload(); }} />}
    </>
  );
}

type ProfileValues = Omit<T.Profile, "id">;
const PROFILE_FIELDS: { key: keyof ProfileValues; label: string; unit: string }[] = [
  { key: "rhWarningPct", label: "RH warning", unit: "%" },
  { key: "rhCriticalPct", label: "RH critical", unit: "%" },
  { key: "marginWarningC", label: "Margin warning", unit: "°C" },
  { key: "marginCriticalC", label: "Margin critical", unit: "°C" },
  { key: "tempMinC", label: "Minimum temperature", unit: "°C" },
  { key: "tempMaxC", label: "Maximum temperature", unit: "°C" },
  { key: "surfaceOffsetC", label: "Surface offset", unit: "°C" },
];

/**
 * Administration > Equipment profiles: edit one profile (PUT /api/v1/profiles/{id}, admins only). The rules live in
 * the backend (ProfileValidator); its 400 message is shown as it is. A confirmation step lists what will change.
 */
export function ProfileDialog({ profile, onClose, onSaved }: { profile: T.Profile; onClose: () => void; onSaved: (p: T.Profile) => void }) {
  const toast = useToast();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries([["name", profile.name], ...PROFILE_FIELDS.map((f) => [f.key, String(profile[f.key])])]));
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const blank = PROFILE_FIELDS.filter((f) => values[f.key].trim() === "" || !Number.isFinite(Number(values[f.key])));
  const changes = [
    ...(values.name.trim() !== profile.name ? [{ label: "Name", from: profile.name, to: values.name.trim() }] : []),
    ...PROFILE_FIELDS.filter((f) => !blank.includes(f) && Number(values[f.key]) !== profile[f.key])
      .map((f) => ({ label: f.label, from: `${profile[f.key]} ${f.unit}`, to: `${Number(values[f.key])} ${f.unit}` })),
  ];
  const body = (): ProfileValues => ({
    name: values.name.trim(),
    ...Object.fromEntries(PROFILE_FIELDS.map((f) => [f.key, Number(values[f.key])])),
  } as ProfileValues);
  const set = (k: string, v: string) => { setValues((old) => ({ ...old, [k]: v })); setServerError(null); };
  const save = async () => {
    setBusy(true);
    try {
      const saved = await updateProfile(profile.id, body());
      toast(`${saved.name} saved`);
      onSaved(saved);
    } catch (err) {
      setServerError(errorMessage(err));
      setConfirming(false);
    } finally { setBusy(false); }
  };
  const footer = confirming
    ? <><Button variant="ghost" disabled={busy} onClick={() => setConfirming(false)}>Back</Button><Button variant="primary" busy={busy} onClick={save}>Save profile</Button></>
    : <><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={blank.length > 0 || changes.length === 0} onClick={() => setConfirming(true)}>Review changes</Button></>;
  return (
    <Modal title={`Edit profile: ${profile.name}`} sub="Thresholds that decide when equipment using this profile shows a warning or a critical risk." onClose={() => !busy && onClose()} footer={footer}>
      {serverError && <div className="callout crit" role="alert"><Icon name="alert" /><div><b>The server did not accept these values.</b> {serverError}</div></div>}
      {confirming ? <>
        <p className="small muted" style={{ margin: 0 }}>Save these changes? They apply to every equipment item with this profile, and the change is recorded in the audit log.</p>
        <table className="dt"><thead><tr><th>Field</th><th>Now</th><th>New</th></tr></thead><tbody>
          {changes.map((c) => <tr key={c.label}><td>{c.label}</td><td className="num">{c.from}</td><td className="num cell-main">{c.to}</td></tr>)}
        </tbody></table>
      </> : <>
        <label className="field">Name<input value={values.name} onChange={(e) => set("name", e.target.value)} maxLength={100} /></label>
        <div className="cols">
          {PROFILE_FIELDS.map((f) => (
            <label key={f.key} className="field">{f.label} ({f.unit})
              <input type="number" inputMode="decimal" step="0.1" value={values[f.key]} aria-invalid={blank.includes(f)} onChange={(e) => set(f.key, e.target.value)} />
              {blank.includes(f) && <span className="field-error" role="alert">Enter a number.</span>}
            </label>
          ))}
        </div>
        <span className="field-hint">The server checks the rules: warning margin above the critical margin, critical margin above 0 °C, warning humidity below critical humidity (at most 100 %), minimum temperature below maximum.</span>
      </>}
    </Modal>
  );
}

const ROLES: T.Role[] = ["TECHNICIAN", "OPERATOR", "MANAGER", "ADMIN"];

export function UsersTab() {
  const u = useAsync(users, []);
  const toast = useToast();
  const { session } = useAuth();
  const [change, setChange] = useState<{ user: T.User; role: T.Role } | null>(null);
  const [saving, setSaving] = useState(false);
  const [unlocking, setUnlocking] = useState<number | null>(null);
  const isMe = (x: T.User) => !!session && session.username.toLowerCase() === (x.email ?? x.username).toLowerCase();

  const saveRole = async () => {
    if (!change) return;
    setSaving(true);
    try {
      await setUserRole(change.user.id, change.role);
      toast(`${change.user.fullName} is now ${label(change.role).toLowerCase()}`);
      setChange(null);
      u.reload();
    } catch (e) { toast(errorMessage(e), true); } finally { setSaving(false); }
  };
  const unlock = async (x: T.User) => {
    setUnlocking(x.id);
    try {
      await unlockUser(x.id);
      toast(`${x.fullName} can sign in again`);
      u.reload();
    } catch (e) { toast(errorMessage(e), true); } finally { setUnlocking(null); }
  };

  if (u.loading) return <PageSkeleton table />;
  if (u.error) return <ErrorState error={u.error} onRetry={u.reload} />;
  if (!u.data?.length) return <div className="card"><Empty icon="user" title="No users yet" text="Accounts appear here once they exist in the team backend." /></div>;
  return (
    <>
      <div className="card flush"><div className="table-wrap"><table className="dt"><thead><tr><th>User</th><th style={{ width: 220 }}>Role</th><th>Last sign-in</th><th>Status</th></tr></thead><tbody>
        {u.data.map((x) => (
          <tr key={x.id}><td><div className="row" style={{ gap: 12, flexWrap: "nowrap" }}><span className="avatar sm">{x.fullName[0]}</span><div><div className="cell-main">{x.fullName}</div><div className="cell-sub">{x.username}</div></div></div></td>
            <td>{isMe(x)
              ? <span title="Ask another administrator to change your role">{label(x.role)} <span className="cell-sub">(you)</span></span>
              : <Dropdown block value={x.role} ariaLabel={`Role for ${x.fullName}`} onChange={(role) => role !== x.role && setChange({ user: x, role })} options={ROLES.map((r) => ({ value: r, label: label(r) }))} />}</td>
            <td className="num small">{x.lastLoginAt ? ago(x.lastLoginAt) : "Never"}</td>
            <td><div className="row" style={{ gap: 10, flexWrap: "nowrap" }}>
              {x.lockedUntil ? <Badge value="CRITICAL" text="Locked" /> : x.enabled ? <Badge value="HEALTHY" text="Active" /> : <Badge text="Disabled" />}
              {x.lockedUntil && <Button size="sm" busy={unlocking === x.id} disabled={unlocking !== null} onClick={() => unlock(x)}>Unlock</Button>}
            </div></td></tr>
        ))}
      </tbody></table></div></div>
      {change && (
        <Modal title="Change role?" sub={`${change.user.fullName}: ${label(change.user.role)} to ${label(change.role)}.`} onClose={() => !saving && setChange(null)}
          footer={<><Button variant="ghost" disabled={saving} onClick={() => setChange(null)}>Cancel</Button><Button variant="primary" busy={saving} onClick={saveRole}>Change role</Button></>}>
          <p className="small muted" style={{ margin: 0 }}>The new role applies from their next sign-in. A session that is already open keeps the old role until it expires (up to 8 hours).</p>
        </Modal>
      )}
    </>
  );
}
