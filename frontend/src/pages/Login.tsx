import { useState, type FormEvent } from "react";
import { artMarkup } from "../components/Art";
import { Icon } from "../components/Icon";
import { Button } from "../components/ui";
import { useAuth } from "../lib/auth";
import { config } from "../lib/config";

const SCENE = `<ellipse cx="260" cy="338" rx="210" ry="12" fill="#1f5fe0" opacity=".08"/><g transform="translate(60 70)">${artMarkup("rack")}</g><g transform="translate(240 130) scale(.92)">${artMarkup("cabinet")}</g>
<g transform="translate(290 26)"><rect width="190" height="92" rx="20" fill="#111318"/><text x="22" y="34" fill="#9aa1b2" font-size="13" font-family="Cabin, sans-serif">Margin to condensation</text><text x="22" y="70" fill="#fff" font-size="32" font-weight="700" font-family="Cabin, sans-serif">6.4 °C</text><circle cx="156" cy="56" r="18" fill="#1f5fe0"/><path d="M156 45 s-7 8 -7 12 a7 7 0 0 0 14 0 c0 -4 -7 -12 -7 -12z" fill="#fff"/></g>`;

export function Login() {
  const { login } = useAuth();
  const [email, setEmail] = useState(config.useMocks ? "tech@corrosionwatch.app" : "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try { await login(email, password); } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  };
  return (
    <main className="login">
      <div className="visual">
        <svg className="scene-art" viewBox="0 0 520 360" aria-hidden="true" dangerouslySetInnerHTML={{ __html: SCENE }} />
        <div className="cap"><h2>Catch condensation before it reaches the board</h2><p>Live dew-point margins for every rack, cabinet and enclosure, with alerts your team can act on and a record you can trust.</p><div className="dots"><i /><i /><i /></div></div>
      </div>
      <div className="form-side">
        <form onSubmit={submit} noValidate>
          <div className="brand" style={{ padding: "0 0 10px" }}><span className="mark"><Icon name="drop" /></span>Corrosion Watch</div>
          <span className="tag">Early warning for electronics</span>
          <div><h1>Welcome back</h1><p className="muted" style={{ margin: "6px 0 0" }}>Sign in to monitor your equipment.</p></div>
          <label className="field">Email<div className="input-icon"><Icon name="user" /><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required /></div></label>
          <label className="field">Password<div className="input-icon"><Icon name="lock" /><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" placeholder="••••••••" required /></div></label>
          {error && <p className="err" role="alert"><Icon name="alert" />{error}</p>}
          <Button variant="primary" className="block" busy={busy} type="submit">{busy ? "Signing in" : "Sign in"}</Button>
          {config.useMocks && <p className="help">Mock backend: sign in as tech@, operator@, manager@ or admin@corrosionwatch.app with any password. Type “wrong” to see the lockout.</p>}
        </form>
      </div>
    </main>
  );
}
