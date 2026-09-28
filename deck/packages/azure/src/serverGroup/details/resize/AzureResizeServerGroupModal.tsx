import React from 'react';
import { Modal } from 'react-bootstrap';

import type { Application, DeckRuntimeServices, IServerGroup } from '@spinnaker/core';
import {
  confirmNotManaged,
  DeckRuntimeContext,
  noop,
  ReactModal,
  TaskMonitor,
  TaskMonitorWrapper,
} from '@spinnaker/core';

export interface IAzureResizeServerGroupModalProps {
  application: Application;
  serverGroup: IServerGroup;
  dismissModal?: () => void;
  closeModal?: () => void;
}

export interface IAzureResizeServerGroupModalState {
  targetSize: number;
  taskMonitor: TaskMonitor;
}

function toCount(value: number | string | undefined): number {
  return typeof value === 'number' ? value : parseInt(value as string, 10) || 0;
}

export class AzureResizeServerGroupModal extends React.Component<
  IAzureResizeServerGroupModalProps,
  IAzureResizeServerGroupModalState
> {
  public static contextType = DeckRuntimeContext;
  public declare context: React.ContextType<typeof DeckRuntimeContext>;

  public static defaultProps: Partial<IAzureResizeServerGroupModalProps> = {
    closeModal: noop,
    dismissModal: noop,
  };

  public static show(props: IAzureResizeServerGroupModalProps, runtimeServices: DeckRuntimeServices) {
    const { serverGroup, application } = props;
    return confirmNotManaged(serverGroup, application).then((notManaged) => {
      notManaged && ReactModal.show(AzureResizeServerGroupModal, props, {}, runtimeServices);
    });
  }

  constructor(props: IAzureResizeServerGroupModalProps) {
    super(props);
    this.state = {
      targetSize: toCount(props.serverGroup.capacity?.desired),
      taskMonitor: new TaskMonitor({
        application: props.application,
        title: `Resizing ${props.serverGroup.name}`,
        onDismiss: () => this.props.dismissModal(),
        onTaskComplete: () => this.props.application.serverGroups.refresh(),
      }),
    };
  }

  private handleSizeChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    this.setState({ targetSize: parseInt(event.target.value, 10) || 0 });
  };

  private submit = () => {
    const { serverGroup, application } = this.props;
    const { targetSize } = this.state;

    // A scale set has a single capacity; send min/max/desired together so the
    // operation cannot leave the bounds disagreeing with the desired count.
    const command = {
      targetSize,
      capacity: { min: targetSize, max: targetSize, desired: targetSize },
    };

    this.state.taskMonitor.submit(() => {
      return this.context.services.serverGroupWriter.resizeServerGroup(serverGroup, application, command);
    });
  };

  private cancel = () => {
    this.props.dismissModal();
  };

  public render() {
    const { serverGroup } = this.props;
    const { targetSize, taskMonitor } = this.state;
    const currentSize = toCount(serverGroup.capacity?.desired);

    return (
      <Modal show={true} onHide={this.cancel}>
        <TaskMonitorWrapper monitor={taskMonitor} />
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
                  className="form-control input-sm"
                  id="targetSize"
                  min="0"
                  onChange={this.handleSizeChange}
                  type="number"
                  value={targetSize}
                />
              </div>
            </div>
          </form>
        </Modal.Body>
        <Modal.Footer>
          <button className="btn btn-default" onClick={this.cancel}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={isNaN(targetSize) || targetSize < 0} onClick={this.submit}>
            Resize
          </button>
        </Modal.Footer>
      </Modal>
    );
  }
}
