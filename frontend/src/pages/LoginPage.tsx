import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from '../api/client'
import { Brand } from '../components/Brand'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    if (!loginName.trim() || !password) {
      setError('введите логин и пароль')
      return
    }
    setBusy(true)
    try {
      const me = await login(loginName.trim(), password)
      navigate(me.role === 'администратор' ? '/admin' : '/', { replace: true })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось войти')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="bg" />
      <div className="auth-top">
        <Brand to="/" />
      </div>
      <div className="auth-content">
        <h1>
          <span>Прогноз окупаемости</span>
          <br />
          <span>рекламных кампаний</span>
        </h1>
        <p>AdForecast оценивает коэффициент окупаемости, интервал прогноза и срок возврата средств до запуска кампании.</p>
        <form className="login-form" onSubmit={submit}>
          {error && <div className="form-error">{error}</div>}
          <input
            type="text"
            placeholder="Логин"
            autoComplete="username"
            value={loginName}
            onChange={(event) => setLoginName(event.target.value)}
          />
          <input
            type="password"
            placeholder="Пароль"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <button className="cta" type="submit" disabled={busy}>
            {busy ? 'Входим…' : 'Войти'}
          </button>
          <div className="hint">
            нет аккаунта?
            <Link to="/register">Зарегистрироваться</Link>
          </div>
        </form>
      </div>
    </>
  )
}
