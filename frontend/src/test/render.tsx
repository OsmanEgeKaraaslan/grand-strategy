import type { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { Session } from '@supabase/supabase-js'
import { vi } from 'vitest'
import { ApiProvider } from '../api/ApiContext'
import type { ApiClient } from '../api/client'
import { AuthContext, type Auth } from '../auth/AuthContext'

export function fakeSession(): Session {
  return {
    access_token: 'test-access-token',
    refresh_token: 'test-refresh-token',
    expires_in: 3600,
    token_type: 'bearer',
    user: { id: '00000000-0000-4000-8000-000000000001' },
  } as Session
}

export function fakeAuth(overrides: Partial<Auth> = {}): Auth {
  return {
    session: fakeSession(),
    loading: false,
    signIn: vi.fn().mockResolvedValue(undefined),
    signUp: vi.fn().mockResolvedValue({ status: 'signed-in' }),
    signOut: vi.fn().mockResolvedValue(undefined),
    accessToken: vi.fn().mockResolvedValue('test-access-token'),
    ...overrides,
  }
}

export function fakeApi(overrides: Partial<ApiClient> = {}): ApiClient {
  const notStubbed = (name: string) => vi.fn().mockRejectedValue(new Error(`${name} not stubbed`))
  return {
    getMe: notStubbed('getMe'),
    updateMe: notStubbed('updateMe'),
    listGames: notStubbed('listGames'),
    getGame: notStubbed('getGame'),
    createGame: notStubbed('createGame'),
    joinGame: notStubbed('joinGame'),
    chooseNation: notStubbed('chooseNation'),
    startGame: notStubbed('startGame'),
    getMap: notStubbed('getMap'),
    getGameState: notStubbed('getGameState'),
    endTurn: notStubbed('endTurn'),
    getEconomy: notStubbed('getEconomy'),
    getCityEconomy: notStubbed('getCityEconomy'),
    buildWorkshop: notStubbed('buildWorkshop'),
    getMilitary: notStubbed('getMilitary'),
    getBattles: notStubbed('getBattles'),
    getBattle: notStubbed('getBattle'),
    recruit: notStubbed('recruit'),
    move: notStubbed('move'),
    regroup: notStubbed('regroup'),
    getBattalion: notStubbed('getBattalion'),
    reinforce: notStubbed('reinforce'),
    disband: notStubbed('disband'),
    getSieges: notStubbed('getSieges'),
    getProvinceFortification: notStubbed('getProvinceFortification'),
    buildFortress: notStubbed('buildFortress'),
    repairFortress: notStubbed('repairFortress'),
    repairWorkshop: notStubbed('repairWorkshop'),
    getDiplomacy: notStubbed('getDiplomacy'),
    listMaps: notStubbed('listMaps'),
    sendGold: notStubbed('sendGold'),
    attach: notStubbed('attach'),
    release: notStubbed('release'),
    callToArms: notStubbed('callToArms'),
    answerCall: notStubbed('answerCall'),
    getConference: notStubbed('getConference'),
    requestConference: notStubbed('requestConference'),
    answerConference: notStubbed('answerConference'),
    cancelConference: notStubbed('cancelConference'),
    submitDraft: notStubbed('submitDraft'),
    decideDraft: notStubbed('decideDraft'),
    getPeaceProposals: notStubbed('getPeaceProposals'),
    getTreaties: notStubbed('getTreaties'),
    getTributes: notStubbed('getTributes'),
    getDiplomacyEvents: notStubbed('getDiplomacyEvents'),
    declareWar: notStubbed('declareWar'),
    proposeAlliance: notStubbed('proposeAlliance'),
    allianceAction: notStubbed('allianceAction'),
    proposePeace: notStubbed('proposePeace'),
    checkPeace: notStubbed('checkPeace'),
    acceptPeace: notStubbed('acceptPeace'),
    peaceAction: notStubbed('peaceAction'),
    counterPeace: notStubbed('counterPeace'),
    getResult: notStubbed('getResult'),
    getResearch: notStubbed('getResearch'),
    getCityInstitutions: notStubbed('getCityInstitutions'),
    buildInstitution: notStubbed('buildInstitution'),
    unlockTechnology: notStubbed('unlockTechnology'),
    unlockDoctrine: notStubbed('unlockDoctrine'),
    upgradeWorkshop: notStubbed('upgradeWorkshop'),
    ...overrides,
  }
}

interface RenderOptions {
  route?: string
  path?: string
  auth?: Auth
  api?: ApiClient
  /** Extra routes so navigation targets render something assertable. */
  extraRoutes?: { path: string; element: ReactElement }[]
}

/** Renders a page inside the same providers the app uses, with fakes for auth and the API. */
export function renderPage(ui: ReactElement, options: RenderOptions = {}) {
  const { route = '/', path = '/', auth = fakeAuth(), api = fakeApi(), extraRoutes = [] } = options
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={auth}>
        <ApiProvider client={api}>
          <MemoryRouter initialEntries={[route]}>
            <Routes>
              <Route path={path} element={ui} />
              {extraRoutes.map((extra) => (
                <Route key={extra.path} path={extra.path} element={extra.element} />
              ))}
            </Routes>
          </MemoryRouter>
        </ApiProvider>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
  return { ...result, auth, api, queryClient }
}
