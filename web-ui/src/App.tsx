import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Layout }         from './components/Layout'
import { Dashboard }      from './pages/Dashboard'
import { Projects }       from './pages/Projects'
import { ProjectDetail }  from './pages/ProjectDetail'
import { RunDetail }      from './pages/RunDetail'
import { Scenarios }      from './pages/Scenarios'
import { ScenarioDetail } from './pages/ScenarioDetail'
import { Nodes }          from './pages/Nodes'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index                        element={<Dashboard />} />
          <Route path="projects"              element={<Projects />} />
          <Route path="projects/:id"          element={<ProjectDetail />} />
          <Route path="runs/:runId"           element={<RunDetail />} />
          <Route path="scenarios"             element={<Scenarios />} />
          <Route path="scenarios/:id"         element={<ScenarioDetail />} />
          <Route path="nodes"                 element={<Nodes />} />
          <Route path="*"                     element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
