import React from 'react';
import { Modal } from 'react-bootstrap';

import type { Application, IServerGroup } from '@spinnaker/core';
import { noop, ReactInjector, ReactModal, TaskMonitor, TaskMonitorWrapper } from '@spinnaker/core';

export interface IAzureResizeServerGroupModalProps {
  application: Application;
  serverGroup: IServerGroup;
  dismissModal?: () => void;
  closeModal?: () => void;
}

export interface IAzureResizeServerGroupModalState {
  targetSize: number;
  taskMonitor: TaskMonitor;
  submitting: boolean;
}

export class AzureResizeServerGroupModal extends React.Component<
  IAzureResizeServerGroupModalProps,
  IAzureResizeServerGroupModalState
> {
  public static defaultProps: Partial<IAzureResizeServerGroupModalProps> = {
    closeModal: noop,
    dismissModal: noop,
  };

  public static show(props: IAzureResizeServerGroupModalProps): Promise<void> {
    return ReactModal.show(AzureResizeServerGroupModal, props);
  }

  constructor(props: IAzureResizeServerGroupModalProps) {
    super(props);
    const currentSize = props.serverGroup.capacity?.desired;
    this.state = {
      targetSize: typeof currentSize === 'number' ? currentSize : parseInt(currentSize as string, 10) || 0,
      taskMonitor: new TaskMonitor({
        application: props.application,
        title: `Resizing ${props.serverGroup.name}`,
        modalInstance: TaskMonitor.modalInstanceEmulation(() => this.props.dismissModal()),
      }),
      submitting: true,
    };
  }

  private handleSizeChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    this.setState({ targetSize: parseInt(event.target.value, 10) || 0 });
  };

  private submit = () => {
    const { serverGroup, application } = this.props;
    const { targetSize, taskMonitor } = this.state;

    const command = {
      targetSize: targetSize,
      capacity: {
        min: targetSize,
        max: targetSize,
        desired: targetSize,
      },
      // Bypass health checks for Azure resize operations
      // Azure handles health checking internally via waitForScaleSetHealthy
      interestingHealthProviderNames: [] as string[],
    };

    this.setState({ submitting: false });

    taskMonitor.submit(() => {
      return ReactInjector.serverGroupWriter.resizeServerGroup(serverGroup, application, command);
    });
  };

  private cancel = () => {
    this.props.dismissModal();
  };

  public render() {
    const { serverGroup } = this.props;
    const { targetSize, submitting, taskMonitor } = this.state;
    const desired = serverGroup.capacity?.desired;
    const currentSize = typeof desired === 'number' ? desired : parseInt(desired as string, 10) || 0;

    return (
      <Modal show={true} onHide={this.cancel}>
        <TaskMonitorWrapper monitor={taskMonitor} />
        {submitting && (
          <>
            <Modal.Header closeButton>
              <Modal.Title>Resize {serverGroup.name}</Modal.Title>
            </Modal.Header>
            <Modal.Body>
              <form className="form-horizontal">
                <div className="form-group">
                  <label className="col-md-4 control-label">Current Size</label>
                  <div className="col-md-6">
                    <p className="form-control-static">{currentSize}</p>
                  </div>
                </div>
                <div className="form-group">
                  <label className="col-md-4 control-label" htmlFor="targetSize">
                    Target Size
                  </label>
                  <div className="col-md-3">
                    <input
                      id="targetSize"
                      type="number"
                      className="form-control input-sm"
                      value={targetSize}
                      onChange={this.handleSizeChange}
                      min="0"
                    />
                  </div>
                </div>
              </form>
            </Modal.Body>
            <Modal.Footer>
              <button className="btn btn-default" onClick={this.cancel}>
                Cancel
              </button>
              <button className="btn btn-primary" onClick={this.submit} disabled={isNaN(targetSize) || targetSize < 0}>
                Resize
              </button>
            </Modal.Footer>
          </>
        )}
      </Modal>
    );
  }
}
