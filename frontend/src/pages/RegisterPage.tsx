import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from '../api/client'
import { Brand } from '../components/Brand'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [fullName, setFullName] = useState('')
  const [loginName, setLoginName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    if (fullName.trim().length < 2) {
      setError('укажите имя и фамилию')
      return
    }
    if (!/^[a-zA-Z0-9._-]{3,50}$/.test(loginName.trim())) {
      setError('логин — от 3 до 50 символов: латиница, цифры, точка, дефис, подчёркивание')
      return
    }
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) {
      setError('укажите корректный e-mail')
      return
    }
    if (password.length < 6) {
      setError('пароль — не менее 6 символов')
      return
    }
    if (password !== confirm) {
      setError('пароли не совпадают')
      return
    }
    setBusy(true)
    try {
      await register({ fullName: fullName.trim(), login: loginName.trim(), email: email.trim(), password })
      navigate('/', { replace: true })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось зарегистрироваться')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="bg" />
      <div className="auth-top">
        <Brand to="/login" />
      </div>
      <div className="auth-main">
        <div className="panel">
          <div className="intro">
            <h1>
              Регистрация
              <br />
              в AdForecast
            </h1>
            <p>Учётная запись нужна, чтобы загружать выгрузки рекламных кабинетов и запускать прогнозы окупаемости по своим кампаниям.</p>
            <ul>
              <li>прогноз окупаемости до старта кампании</li>
              <li>вероятность возврата средств и срок окупаемости</li>
              <li>отчёты в XLSX и PDF для согласования бюджета</li>
            </ul>
          </div>
          <div className="form-card">
            <h2>Новая учётная запись</h2>
            <div className="csub">все поля обязательны</div>
            <form className="form" onSubmit={submit}>
              {error && <div className="form-error">{error}</div>}
              <div className="field">
                <label>Имя и фамилия</label>
                <input placeholder="Иван Иванов" value={fullName} onChange={(event) => setFullName(event.target.value)} />
              </div>
              <div className="field">
                <label>Логин</label>
                <input placeholder="iivanov" autoComplete="username" value={loginName} onChange={(event) => setLoginName(event.target.value)} />
              </div>
              <div className="field">
                <label>E-mail</label>
                <input type="email" placeholder="ivanov@adforecast.by" value={email} onChange={(event) => setEmail(event.target.value)} />
              </div>
              <div className="field">
                <label>Пароль</label>
                <input type="password" placeholder="не менее 6 символов" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} />
              </div>
              <div className="field">
                <label>Подтверждение пароля</label>
                <input type="password" placeholder="повторите пароль" autoComplete="new-password" value={confirm} onChange={(event) => setConfirm(event.target.value)} />
              </div>
              <button className="cta" type="submit" disabled={busy}>
                {busy ? 'Создаём…' : 'Зарегистрироваться'}
              </button>
              <div className="hint">
                уже есть аккаунт?
                <Link to="/login">Войти</Link>
              </div>
            </form>
          </div>
        </div>
      </div>
    </>
  )
}
