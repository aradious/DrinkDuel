import { Component, input } from '@angular/core';
import { SheriffPlayingView, SheriffRole } from '../core/room.models';

@Component({
  selector: 'dd-sheriff-playing-status',
  styleUrl: './sheriff-playing-status.scss',
  template: `
    <section class="role-status" aria-labelledby="role-status-title">
      <div class="status-heading">
        <div>
          <p>LIVE ROLE STATUS</p>
          <h2 id="role-status-title">Town count</h2>
        </div>
        <div class="overall-status" aria-label="Overall player status">
          <span
            ><strong>{{ game().overallStatus.total }}</strong> Total</span
          >
          <span
            ><strong>{{ game().overallStatus.alive }}</strong> Alive</span
          >
          <span
            ><strong>{{ game().overallStatus.dead }}</strong> Dead</span
          >
        </div>
      </div>
      <div class="role-count-grid">
        @for (status of game().roleStatus; track status.role) {
          <article class="role-count">
            <strong>{{ roleLabel(status.role) }}</strong>
            <dl>
              <div>
                <dt>Assigned</dt>
                <dd>{{ status.assigned }}</dd>
              </div>
              <div>
                <dt>Alive</dt>
                <dd>{{ status.alive }}</dd>
              </div>
              <div>
                <dt>Dead</dt>
                <dd>{{ status.dead }}</dd>
              </div>
            </dl>
          </article>
        }
      </div>
    </section>
  `,
})
export class SheriffPlayingStatus {
  readonly game = input.required<SheriffPlayingView>();

  roleLabel(role: SheriffRole): string {
    return role.charAt(0) + role.slice(1).toLowerCase();
  }
}
